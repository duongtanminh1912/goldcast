package vn.goldcast.market;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.goldcast.api.ResourceNotFoundException;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.InstrumentKind;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.ingest.SyntheticSeedGenerator;
import vn.goldcast.repository.InstrumentRepository;
import vn.goldcast.repository.PricePointRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Loading and shaping price history. Everything that needs a series goes through here, so
 * ordering, truncation and the "is this synthetic?" question are answered one way only.
 */
@Service
@Transactional(readOnly = true)
public class PriceSeriesService {

    private final InstrumentRepository instruments;
    private final PricePointRepository prices;

    public PriceSeriesService(InstrumentRepository instruments, PricePointRepository prices) {
        this.instruments = instruments;
        this.prices = prices;
    }

    public Instrument requireInstrument(String code) {
        return instruments.findByCodeIgnoreCase(code)
                .orElseThrow(() -> ResourceNotFoundException.instrument(code));
    }

    public List<Instrument> activeInstruments() {
        return instruments.findByActiveTrueOrderBySortOrderAscCodeAsc();
    }

    public List<Instrument> instrumentsOfKind(InstrumentKind kind) {
        return instruments.findByKindAndActiveTrueOrderBySortOrderAsc(kind);
    }

    /**
     * The most recent {@code maxPoints} observations, oldest first.
     *
     * <p>Fetched newest-first and reversed: taking the tail of a long series should not
     * mean sorting the whole thing.
     */
    public List<PricePoint> recent(Long instrumentId, int maxPoints) {
        List<PricePoint> descending = prices.findRecent(instrumentId, PageRequest.of(0, maxPoints));
        List<PricePoint> ascending = new ArrayList<>(descending);
        ascending.sort(Comparator.comparing(PricePoint::getObservedOn));
        return ascending;
    }

    public List<PricePoint> range(Long instrumentId, LocalDate from, LocalDate to) {
        return prices.findByInstrumentIdAndObservedOnBetweenOrderByObservedOnAsc(instrumentId, from, to);
    }

    public Optional<PricePoint> latest(Long instrumentId) {
        return prices.findTopByInstrumentIdOrderByObservedOnDesc(instrumentId);
    }

    /**
     * The observation in effect on a given day — the most recent one at or before it.
     *
     * <p>Needed to line two series up: a Saturday dealer quote has no same-day world
     * price, and using Monday's would be looking into the future.
     */
    public Optional<PricePoint> asOf(Long instrumentId, LocalDate date) {
        return prices.findTopByInstrumentIdAndObservedOnLessThanEqualOrderByObservedOnDesc(
                instrumentId, date);
    }

    public long count(Long instrumentId) {
        return prices.countByInstrumentId(instrumentId);
    }

    /** Extracts the canonical close series as primitives for the forecasting engine. */
    public static double[] closes(List<PricePoint> points) {
        double[] out = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            out[i] = points.get(i).getClosePrice().doubleValue();
        }
        return out;
    }

    public static List<LocalDate> dates(List<PricePoint> points) {
        return points.stream().map(PricePoint::getObservedOn).toList();
    }

    /** Distinct provider ids behind a series, in the order first seen. */
    public static List<String> sources(List<PricePoint> points) {
        Set<String> seen = new LinkedHashSet<>();
        for (PricePoint point : points) {
            seen.add(point.getSource());
        }
        return List.copyOf(seen);
    }

    public static boolean containsSynthetic(List<PricePoint> points) {
        return points.stream().anyMatch(p -> SyntheticSeedGenerator.SOURCE.equals(p.getSource()));
    }

    /** Percentage change over {@code lookbackDays} calendar days, or {@code null}. */
    public static Double changePercentOverDays(List<PricePoint> points, int lookbackDays) {
        if (points.isEmpty()) {
            return null;
        }
        PricePoint last = points.get(points.size() - 1);
        LocalDate target = last.getObservedOn().minusDays(lookbackDays);

        PricePoint reference = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (!points.get(i).getObservedOn().isAfter(target)) {
                reference = points.get(i);
                break;
            }
        }
        if (reference == null || reference.getClosePrice().signum() == 0) {
            return null;
        }

        BigDecimal previous = reference.getClosePrice();
        return last.getClosePrice().subtract(previous)
                .divide(previous, 10, java.math.RoundingMode.HALF_UP)
                .doubleValue() * 100.0;
    }
}
