package vn.goldcast.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.InstrumentKind;
import vn.goldcast.ingest.IngestService;
import vn.goldcast.ingest.PriceProvider;
import vn.goldcast.macro.MacroIngest;
import vn.goldcast.macro.MacroProperties;
import vn.goldcast.macro.MacroSeries;
import vn.goldcast.macro.MacroStore;
import vn.goldcast.market.DataFreshness;
import vn.goldcast.market.PriceSeriesService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Scraping never queries the DB. Failed refreshes retain cached values and expose a failure/staleness signal. */
@Component
public class GoldcastMetrics implements MeterBinder {
    private static final Logger log = LoggerFactory.getLogger(GoldcastMetrics.class);
    private final PriceSeriesService series;
    private final IngestService ingest;
    private final AppProperties properties;
    private final List<PriceProvider> providers;
    private final MacroStore macroStore;
    private final MacroProperties macroProperties;
    private final String releaseId;
    private final Map<String, QuoteState> quotes = new ConcurrentHashMap<>();
    private final Map<MacroSeries, MacroState> macro = new EnumMap<>(MacroSeries.class);
    private MeterRegistry registry;
    private volatile double refreshSuccess;
    private volatile double refreshTimestamp;
    private final Clock clock;

    @Autowired
    public GoldcastMetrics(PriceSeriesService series, IngestService ingest, AppProperties properties,
                          List<PriceProvider> providers, MacroStore macroStore, MacroProperties macroProperties,
                          @Value("${app.release-id:unknown}") String releaseId) {
        this(series, ingest, properties, providers, macroStore, macroProperties, releaseId,
                Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    GoldcastMetrics(PriceSeriesService series, IngestService ingest, AppProperties properties,
                   List<PriceProvider> providers, MacroStore macroStore, MacroProperties macroProperties,
                   String releaseId, Clock clock) {
        this.series = series;
        this.ingest = ingest;
        this.properties = properties;
        this.providers = providers;
        this.macroStore = macroStore;
        this.macroProperties = macroProperties;
        this.releaseId = releaseId;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("goldcast.release.info", () -> 1).tag("release_id", releaseId).register(registry);
        Gauge.builder("goldcast.market.refresh.success", () -> refreshSuccess).register(registry);
        Gauge.builder("goldcast.market.refresh.timestamp", () -> refreshTimestamp).register(registry);
        Gauge.builder("goldcast.ingest.enabled", () -> properties.ingest().enabled() ? 1 : 0).register(registry);
        Gauge.builder("goldcast.ingest.running", () -> ingest.status().running() ? 1 : 0).register(registry);
        Gauge.builder("goldcast.ingest.last.attempt.timestamp", () -> ingest.status().lastAttempt() == null ? 0
                : ingest.status().lastAttempt().toEpochSecond()).register(registry);
        Gauge.builder("goldcast.ingest.last.transaction.failed", () -> ingest.status().lastTransactionFailed() ? 1 : 0).register(registry);
        for (PriceProvider provider : providers) {
            registerProvider(provider.id(), provider::enabled);
        }
        // FRED is not a PriceProvider, but runs inside the same ingest and reports the same outcome.
        registerProvider(MacroIngest.PROVIDER_ID, macroProperties::enabled);
        Gauge.builder("goldcast.macro.enabled", () -> macroProperties.enabled() ? 1 : 0).register(registry);
        Gauge.builder("goldcast.macro.max.lag.days", macroProperties::maxLagDays).register(registry);
        for (MacroSeries driver : MacroSeries.values()) {
            MacroState state = new MacroState();
            Gauge.builder("goldcast.macro.observation.present", state, s -> s.present)
                    .tag("series", driver.fredId()).register(registry);
            Gauge.builder("goldcast.macro.observation.lag.days", state, s -> s.lag)
                    .tag("series", driver.fredId()).register(registry);
            macro.put(driver, state);
        }
    }

    private void registerProvider(String id, java.util.function.BooleanSupplier enabled) {
        Gauge.builder("goldcast.ingest.provider.enabled", () -> enabled.getAsBoolean() ? 1 : 0)
                .tag("provider", id).register(registry);
        Gauge.builder("goldcast.ingest.provider.last.success.timestamp", () -> {
            var success = ingest.status().lastProviderSuccess().get(id);
            return success == null ? 0 : success.toEpochSecond();
        }).tag("provider", id).register(registry);
    }

    @Scheduled(fixedDelayString = "${app.monitoring.refresh-millis:60000}", initialDelay = 1000)
    public synchronized void refresh() {
        if (registry == null) return;
        try {
            var instruments = series.activeInstruments();
            LocalDate today = LocalDate.now(clock);
            LocalDate goldClose = null;
            for (var instrument : instruments) {
                QuoteState state = quotes.computeIfAbsent(instrument.getCode(), this::registerQuote);
                var latest = series.latest(instrument.getId());
                if (latest.isEmpty()) {
                    state.present = 0;
                    state.lag = 0;
                    state.synthetic = 0;
                    state.future = 0;
                } else {
                    var point = latest.get();
                    state.present = 1;
                    state.lag = DataFreshness.publicationDaysBehind(instrument.getKind(), point.getObservedOn(), today);
                    state.synthetic = "synthetic".equals(point.getSource()) ? 1 : 0;
                    state.future = point.getObservedOn().isAfter(today) ? 1 : 0;
                    if (instrument.getKind() == InstrumentKind.SPOT_GOLD) {
                        goldClose = point.getObservedOn();
                    }
                }
            }
            // Keep label cardinality bounded to active instruments, including removals.
            var active = instruments.stream().map(i -> i.getCode()).toList();
            for (String removed : quotes.keySet().stream().filter(code -> !active.contains(code)).toList()) {
                registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("goldcast.market.observation.")
                                && removed.equals(m.getId().getTag("instrument")))
                        .toList().forEach(registry::remove);
                quotes.remove(removed);
            }
            refreshMacro(goldClose == null ? today : goldClose);
            refreshTimestamp = clock.instant().getEpochSecond();
            refreshSuccess = 1;
        } catch (RuntimeException ex) {
            refreshSuccess = 0;
            log.warn("Monitoring cannot refresh market observations: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Lag is counted the way ForecastService decides whether ARX_MACRO is offered: calendar
     * days from the newest driver value to the last world gold close (today when gold is missing).
     */
    private void refreshMacro(LocalDate reference) {
        for (var entry : macro.entrySet()) {
            var latest = macroStore.latestDate(entry.getKey());
            MacroState state = entry.getValue();
            state.present = latest.isPresent() ? 1 : 0;
            state.lag = latest.map(d -> Math.max(0, ChronoUnit.DAYS.between(d, reference))).orElse(0L);
        }
    }

    private QuoteState registerQuote(String code) {
        QuoteState state = new QuoteState();
        Gauge.builder("goldcast.market.observation.present", state, s -> s.present).tag("instrument", code).register(registry);
        Gauge.builder("goldcast.market.observation.lag.days", state, s -> s.lag).tag("instrument", code).register(registry);
        Gauge.builder("goldcast.market.observation.synthetic", state, s -> s.synthetic).tag("instrument", code).register(registry);
        Gauge.builder("goldcast.market.observation.future", state, s -> s.future).tag("instrument", code).register(registry);
        return state;
    }

    private static final class MacroState {
        volatile double present;
        volatile double lag;
    }

    private static final class QuoteState {
        volatile double present;
        volatile double lag;
        volatile double synthetic;
        volatile double future;
    }
}
