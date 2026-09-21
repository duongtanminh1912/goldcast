package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.repository.InstrumentRepository;
import vn.goldcast.repository.PricePointRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pulls observations from every enabled provider and writes them into the price series.
 *
 * <p>Writes are idempotent: the unique constraint on (instrument, date) is the source of
 * truth, and re-running a pass over data already stored changes nothing. That matters
 * because the scheduler runs hourly against feeds that mostly publish once a day.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final InstrumentRepository instruments;
    private final PricePointRepository prices;
    private final List<PriceProvider> providers;
    private final SyntheticSeedGenerator seedGenerator;
    private final AppProperties properties;

    public IngestService(InstrumentRepository instruments,
                         PricePointRepository prices,
                         List<PriceProvider> providers,
                         SyntheticSeedGenerator seedGenerator,
                         AppProperties properties) {
        this.instruments = instruments;
        this.prices = prices;
        this.providers = providers;
        this.seedGenerator = seedGenerator;
        this.properties = properties;
    }

    @Transactional
    public IngestReport run() {
        OffsetDateTime startedAt = OffsetDateTime.now();
        List<Instrument> active = instruments.findByActiveTrueOrderBySortOrderAscCodeAsc();
        Map<String, Instrument> byCode = new HashMap<>();
        for (Instrument instrument : active) {
            byCode.put(instrument.getCode().toUpperCase(), instrument);
        }

        List<IngestReport.ProviderOutcome> outcomes = new ArrayList<>();
        AtomicInteger totalInserted = new AtomicInteger();
        AtomicInteger totalUpdated = new AtomicInteger();

        for (PriceProvider provider : providers) {
            List<String> codes = active.stream()
                    .filter(instrument -> provider.id().equalsIgnoreCase(instrument.getSource()))
                    .map(Instrument::getCode)
                    .toList();

            if (codes.isEmpty()) {
                continue;
            }
            if (!provider.enabled()) {
                outcomes.add(new IngestReport.ProviderOutcome(
                        provider.id(), false, 0, 0, 0, "Provider đang tắt trong cấu hình"));
                continue;
            }

            List<ProviderQuote> quotes;
            try {
                quotes = provider.fetch(codes, properties.ingest().historyDays());
            } catch (RuntimeException ex) {
                log.warn("Provider {} lỗi: {}", provider.id(), ex.getMessage());
                outcomes.add(new IngestReport.ProviderOutcome(
                        provider.id(), true, 0, 0, 0, "Lỗi: " + ex.getMessage()));
                continue;
            }

            WriteResult result = write(quotes, byCode, provider.id());
            totalInserted.addAndGet(result.inserted());
            totalUpdated.addAndGet(result.updated());
            outcomes.add(new IngestReport.ProviderOutcome(
                    provider.id(), true, quotes.size(), result.inserted(), result.updated(),
                    quotes.isEmpty() ? "Không nhận được dữ liệu từ nguồn" : "OK"));
        }

        int seeded = 0;
        if (properties.ingest().seedOnEmpty()) {
            seeded = seedEmptySeries(active, byCode, totalInserted);
        }

        IngestReport report = new IngestReport(
                startedAt, OffsetDateTime.now(), List.copyOf(outcomes),
                totalInserted.get(), totalUpdated.get(), seeded);

        log.info("Ingest xong trong {}ms: +{} mới, {} cập nhật, {} chuỗi được seed",
                report.durationMillis(), report.inserted(), report.updated(), report.seededInstruments());
        return report;
    }

    /**
     * Fills in any instrument that still has no history at all.
     *
     * <p>Only empty series are touched — a series with even one real observation is never
     * mixed with synthetic data, because a chart blending the two would be misleading in a
     * way no disclaimer can fix.
     */
    private int seedEmptySeries(List<Instrument> active, Map<String, Instrument> byCode,
                                AtomicInteger totalInserted) {
        List<Instrument> empty = active.stream()
                .filter(instrument -> prices.countByInstrumentId(instrument.getId()) == 0)
                .toList();
        if (empty.isEmpty()) {
            return 0;
        }

        log.warn("{} chuỗi chưa có dữ liệu thật ({}), sinh dữ liệu mô phỏng để ứng dụng chạy được. "
                        + "Tắt bằng APP_INGEST_SEED_ON_EMPTY=false.",
                empty.size(), empty.stream().map(Instrument::getCode).toList());

        List<ProviderQuote> synthetic = seedGenerator.generate(empty, properties.ingest().historyDays());
        WriteResult result = write(synthetic, byCode, SyntheticSeedGenerator.SOURCE);
        totalInserted.addAndGet(result.inserted());
        return empty.size();
    }

    private WriteResult write(List<ProviderQuote> quotes, Map<String, Instrument> byCode, String source) {
        if (quotes.isEmpty()) {
            return new WriteResult(0, 0);
        }

        // Pre-load the dates already stored per instrument so the common case — a quote we
        // already have — costs no database round trip at all.
        Map<Long, Set<LocalDate>> known = new HashMap<>();
        LocalDate earliest = quotes.stream()
                .map(ProviderQuote::observedOn)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());

        int inserted = 0;
        int updated = 0;
        List<PricePoint> pending = new ArrayList<>();

        for (ProviderQuote quote : quotes) {
            Instrument instrument = byCode.get(quote.instrumentCode().toUpperCase());
            if (instrument == null) {
                log.warn("Bỏ qua quote cho instrument chưa khai báo: {}", quote.instrumentCode());
                continue;
            }

            Set<LocalDate> dates = known.computeIfAbsent(instrument.getId(),
                    id -> new HashSet<>(prices.findObservedDatesSince(id, earliest)));

            if (!dates.contains(quote.observedOn())) {
                pending.add(new PricePoint(instrument, quote.observedOn(), quote.close(),
                        quote.buy(), quote.sell(), source));
                dates.add(quote.observedOn());
                inserted++;
                continue;
            }

            // Already stored. Dealers revise the same day's quote, so refresh when it moved.
            Optional<PricePoint> existing =
                    prices.findByInstrumentIdAndObservedOn(instrument.getId(), quote.observedOn());
            if (existing.isPresent() && hasChanged(existing.get(), quote)) {
                PricePoint point = existing.get();
                point.setClosePrice(quote.close());
                point.setBuyPrice(quote.buy());
                point.setSellPrice(quote.sell());
                point.setSource(source);
                updated++;
            }
        }

        if (!pending.isEmpty()) {
            prices.saveAll(pending);
        }
        return new WriteResult(inserted, updated);
    }

    private static boolean hasChanged(PricePoint existing, ProviderQuote quote) {
        return differs(existing.getClosePrice(), quote.close())
                || differs(existing.getBuyPrice(), quote.buy())
                || differs(existing.getSellPrice(), quote.sell());
    }

    private static boolean differs(BigDecimal stored, BigDecimal incoming) {
        if (stored == null || incoming == null) {
            return stored != incoming;
        }
        return stored.compareTo(incoming) != 0;
    }

    private record WriteResult(int inserted, int updated) {}
}
