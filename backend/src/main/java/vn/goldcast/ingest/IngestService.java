package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.macro.MacroIngest;
import vn.goldcast.repository.InstrumentRepository;
import vn.goldcast.repository.PricePointRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final TransactionTemplate transactions;
    private final MacroIngest macroIngest;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, OffsetDateTime> lastProviderSuccess = new HashMap<>();
    private OffsetDateTime lastAttempt;
    private IngestReport lastCommittedReport;
    private boolean lastTransactionFailed;

    public IngestService(InstrumentRepository instruments,
                         PricePointRepository prices,
                         List<PriceProvider> providers,
                         SyntheticSeedGenerator seedGenerator,
                         AppProperties properties,
                         PlatformTransactionManager transactionManager,
                         MacroIngest macroIngest) {
        this.instruments = instruments;
        this.prices = prices;
        this.providers = providers;
        this.seedGenerator = seedGenerator;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.macroIngest = macroIngest;
    }

    public IngestReport run() {
        if (!running.compareAndSet(false, true)) {
            throw new IngestAlreadyRunningException();
        }
        synchronized (this) { lastAttempt = OffsetDateTime.now(); }
        try {
            // Network first, with no transaction or connection held: a provider may take up
            // to its timeout, and a pooled connection idling through that helps nobody.
            Fetched fetched = fetch();
            // execute returns only after commit/rollback. Releasing the guard in
            // a @Transactional method's finally would be too early: its proxy
            // commits after the method has returned.
            IngestReport report = transactions.execute(status -> store(fetched));
            // Macro drivers run after prices have committed, in their own transactions,
            // so a FRED outage or a bad row can never roll back price data.
            if (macroIngest != null) {
                List<IngestReport.ProviderOutcome> outcomes = new ArrayList<>(report.providers());
                IngestReport.ProviderOutcome macro = macroIngest.refresh(properties.ingest().seedOnEmpty());
                outcomes.add(macro);
                report = new IngestReport(report.startedAt(), OffsetDateTime.now(), List.copyOf(outcomes),
                        report.inserted() + macro.inserted(), report.updated() + macro.updated(),
                        report.seededInstruments());
            }
            synchronized (this) {
                lastCommittedReport = report;
                lastTransactionFailed = false;
                for (var outcome : report.providers()) {
                    // Fetching unchanged rows is successful; an empty/failed feed is not.
                    if (outcome.enabled() && outcome.quotes() > 0) {
                        lastProviderSuccess.put(outcome.provider(), report.finishedAt());
                    }
                }
            }
            return report;
        } catch (RuntimeException ex) {
            synchronized (this) { lastTransactionFailed = true; }
            throw ex;
        } finally {
            running.set(false);
        }
    }

    /** Process-local telemetry: restart resets it; persisted quote dates remain the freshness truth. */
    public synchronized Status status() {
        return new Status(running.get(), lastAttempt, lastTransactionFailed,
                Map.copyOf(lastProviderSuccess), lastCommittedReport);
    }

    public record Status(boolean running, OffsetDateTime lastAttempt, boolean lastTransactionFailed,
                         Map<String, OffsetDateTime> lastProviderSuccess, IngestReport lastCommittedReport) {}

    /** What the providers returned, gathered before any write. */
    private record Fetched(OffsetDateTime startedAt, List<Instrument> active, Map<String, Instrument> byCode,
                           List<Pass> passes) {}

    /** One provider's result: quotes to write, or a finished outcome when there is nothing to write. */
    private record Pass(PriceProvider provider, List<ProviderQuote> quotes, IngestReport.ProviderOutcome outcome) {}

    private Fetched fetch() {
        OffsetDateTime startedAt = OffsetDateTime.now();
        List<Instrument> active = instruments.findByActiveTrueOrderBySortOrderAscCodeAsc();
        Map<String, Instrument> byCode = new HashMap<>();
        for (Instrument instrument : active) {
            byCode.put(instrument.getCode().toUpperCase(), instrument);
        }

        List<Pass> passes = new ArrayList<>();
        for (PriceProvider provider : providers) {
            List<String> codes = active.stream()
                    .filter(instrument -> provider.id().equalsIgnoreCase(instrument.getSource()))
                    .map(Instrument::getCode)
                    .toList();

            if (codes.isEmpty()) {
                continue;
            }
            if (!provider.enabled()) {
                passes.add(new Pass(provider, List.of(), new IngestReport.ProviderOutcome(
                        provider.id(), false, 0, 0, 0, "Provider đang tắt trong cấu hình")));
                continue;
            }

            try {
                passes.add(new Pass(provider, provider.fetch(codes, properties.ingest().historyDays()), null));
            } catch (RuntimeException ex) {
                log.warn("Provider {} lỗi: {}", provider.id(), ex.getMessage());
                passes.add(new Pass(provider, List.of(), new IngestReport.ProviderOutcome(
                        provider.id(), true, 0, 0, 0, "Lỗi: " + ex.getMessage())));
            }
        }
        return new Fetched(startedAt, active, byCode, List.copyOf(passes));
    }

    private IngestReport store(Fetched fetched) {
        OffsetDateTime startedAt = fetched.startedAt();
        List<Instrument> active = fetched.active();
        Map<String, Instrument> byCode = fetched.byCode();

        List<IngestReport.ProviderOutcome> outcomes = new ArrayList<>();
        AtomicInteger totalInserted = new AtomicInteger();
        AtomicInteger totalUpdated = new AtomicInteger();

        for (Pass pass : fetched.passes()) {
            if (pass.outcome() != null) {
                outcomes.add(pass.outcome());
                continue;
            }
            PriceProvider provider = pass.provider();
            List<ProviderQuote> quotes = pass.quotes();
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
     * <p>Only empty series are seeded. A later provider pass can add real data
     * beside this history; existing synthetic points are not automatically deleted.
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

        // Pre-load the rows already stored per instrument, once, so the common case on an
        // hourly run (a quote we already have) costs no further database round trip.
        Map<Long, Map<LocalDate, PricePoint>> known = new HashMap<>();
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

            Map<LocalDate, PricePoint> stored = known.computeIfAbsent(instrument.getId(), id -> {
                Map<LocalDate, PricePoint> byDate = new HashMap<>();
                for (PricePoint point : prices.findSince(id, earliest)) {
                    byDate.put(point.getObservedOn(), point);
                }
                return byDate;
            });

            PricePoint existing = stored.get(quote.observedOn());
            if (existing == null) {
                PricePoint point = new PricePoint(instrument, quote.observedOn(), quote.close(),
                        quote.buy(), quote.sell(), source);
                pending.add(point);
                stored.put(quote.observedOn(), point);
                inserted++;
                continue;
            }

            // Already stored. Dealers revise the same day's quote, so refresh when it moved.
            // The row is managed by this transaction, so the change is flushed at commit.
            if (hasChanged(existing, quote)) {
                PricePoint point = existing;
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
