package vn.goldcast.market.live;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.goldcast.api.dto.LiveGoldDto;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LiveGoldService implements MeterBinder {
    private static final Logger log = LoggerFactory.getLogger(LiveGoldService.class);
    private final LiveGoldProperties properties;
    private final GoldApiClient client;
    private final LiveGoldStore store;
    private final Clock clock;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile State state = new State(null, null, false, null);

    @Autowired
    public LiveGoldService(LiveGoldProperties properties, GoldApiClient client, LiveGoldStore store) {
        this(properties, client, store, Clock.systemUTC());
    }

    LiveGoldService(LiveGoldProperties properties, GoldApiClient client, LiveGoldStore store, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${app.live-gold.poll-seconds:60}", initialDelay = 1, timeUnit = TimeUnit.SECONDS)
    public void poll() {
        if (!properties.enabled() || !polling.compareAndSet(false, true)) return;
        Instant attempt = clock.instant();
        State previous = state;
        // Publish timestamp and outcome atomically when this attempt has settled.
        try {
            var quote = client.fetch();
            Instant received = clock.instant();
            if (quote.observedAt().isAfter(received.plusSeconds(30))) {
                throw new IllegalArgumentException("Source timestamp is in the future");
            }
            store.save(new LiveGoldObservation(quote.usdPerOunce(), quote.observedAt(), received), properties.retentionDays());
            // save() returns after transaction commit. Neither HTTP success nor receipt time
            // can replace the source observation time used by freshness checks.
            var rows = store.recent(1);
            state = new State(attempt, clock.instant(), true, rows.isEmpty() ? null : rows.getFirst());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            LiveGoldObservation cached = previous.latest;
            try {
                var rows = store.recent(1);
                if (!rows.isEmpty()) cached = rows.getFirst();
            } catch (RuntimeException ignored) { /* Keep cached telemetry, never fabricate freshness. */ }
            state = new State(attempt, previous.lastSuccess, false, cached);
            log.warn("Live gold refresh failed: {}", ex.getClass().getSimpleName());
        } finally {
            polling.set(false);
        }
    }

    public LiveGoldDto snapshot(int limit) {
        var rows = store.recent(limit);
        var latest = rows.isEmpty() ? null : rows.getFirst();
        Instant now = clock.instant();
        boolean session = GoldReferenceSession.expectsUpdates(now);
        String status = !properties.enabled() ? "DISABLED" : latest == null ? "UNAVAILABLE"
                : now.isAfter(latest.observedAt().plusSeconds(properties.staleSeconds()))
                ? (session ? "STALE" : "SESSION_PAUSE") : "FRESH";
        var points = new ArrayList<>(rows.stream().map(LiveGoldService::dto).toList());
        Collections.reverse(points);
        State telemetry = state;
        return new LiveGoldDto("XAUUSD", "USD", "USD_PER_TROY_OUNCE", "gold-api.com", "https://gold-api.com/",
                properties.enabled(), properties.pollSeconds(), properties.staleSeconds(), session, status,
                at(now), at(telemetry.lastAttempt), at(telemetry.lastSuccess), telemetry.successful,
                latest == null ? null : dto(latest), points);
    }

    private static LiveGoldDto.LiveGoldPoint dto(LiveGoldObservation quote) {
        return new LiveGoldDto.LiveGoldPoint(quote.usdPerOunce(), at(quote.observedAt()), at(quote.receivedAt()));
    }

    private static OffsetDateTime at(Instant instant) { return instant == null ? null : instant.atOffset(ZoneOffset.UTC); }
    private static double timestamp(Instant instant) { return instant == null ? 0 : instant.getEpochSecond(); }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("goldcast.live.gold.enabled", () -> properties.enabled() ? 1 : 0).register(registry);
        Gauge.builder("goldcast.live.gold.expected.updates", () -> GoldReferenceSession.expectsUpdates(clock.instant()) ? 1 : 0).register(registry);
        Gauge.builder("goldcast.live.gold.stale.after.seconds", properties::staleSeconds).register(registry);
        Gauge.builder("goldcast.live.gold.last.attempt.timestamp", () -> timestamp(state.lastAttempt)).register(registry);
        Gauge.builder("goldcast.live.gold.last.fetch.timestamp", () -> timestamp(state.lastSuccess)).register(registry);
        Gauge.builder("goldcast.live.gold.fetch.success", () -> state.successful ? 1 : 0).register(registry);
        Gauge.builder("goldcast.live.gold.observed.timestamp", () -> state.latest == null ? 0 : timestamp(state.latest.observedAt())).register(registry);
        Gauge.builder("goldcast.live.gold.usd.per.ounce", () -> state.latest == null ? Double.NaN : state.latest.usdPerOunce().doubleValue()).register(registry);
    }

    private record State(Instant lastAttempt, Instant lastSuccess, boolean successful, LiveGoldObservation latest) {}
}
