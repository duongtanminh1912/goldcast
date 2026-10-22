package vn.goldcast.market.live;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveGoldServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T12:00:00Z"));
    private final GoldApiClient client = mock(GoldApiClient.class);
    private final LiveGoldStore store = mock(LiveGoldStore.class);
    private final LiveGoldProperties properties = new LiveGoldProperties(true, "https://example.test", 60, 10, 180, 30);
    private final LiveGoldService service = new LiveGoldService(properties, client, store, clock);
    private final LiveGoldObservation cached = new LiveGoldObservation(new BigDecimal("4000.123456"), clock.instant(), clock.instant());

    @BeforeEach
    void setup() throws Exception {
        when(client.fetch()).thenReturn(new GoldApiClient.Quote(cached.usdPerOunce(), cached.observedAt()));
        when(store.recent(anyInt())).thenReturn(List.of(cached));
    }

    @Test
    void repeatedReceiptDoesNotMakeOldSourceTimestampFresh() {
        service.poll();
        clock.now = clock.now.plusSeconds(600);
        service.poll();
        var snapshot = service.snapshot(120);
        assertEquals("STALE", snapshot.status());
        assertEquals(cached.observedAt(), snapshot.latest().observedAt().toInstant());
        assertEquals(clock.instant(), snapshot.lastFetchAt().toInstant());
        assertTrue(snapshot.lastFetchSucceeded());
    }

    @Test
    void failedFetchKeepsCachedQuoteAndLastSuccess() throws Exception {
        service.poll();
        clock.now = clock.now.plusSeconds(60);
        when(client.fetch()).thenThrow(new IOException("private upstream detail"));
        service.poll();
        var snapshot = service.snapshot(120);
        assertFalse(snapshot.lastFetchSucceeded());
        assertEquals(cached.observedAt(), snapshot.lastFetchAt().toInstant());
        assertEquals(cached.usdPerOunce(), snapshot.latest().usdPerOunce());
    }

    @Test
    void failedCommitCannotAdvanceSuccessTelemetry() {
        service.poll();
        clock.now = clock.now.plusSeconds(60);
        doThrow(new IllegalStateException("DB write failed")).when(store).save(any(), anyInt());
        service.poll();
        var snapshot = service.snapshot(120);
        assertFalse(snapshot.lastFetchSucceeded());
        assertEquals(cached.observedAt(), snapshot.lastFetchAt().toInstant());
    }

    @Test
    void futureSourceTimestampIsRejectedBeforeDatabaseWrite() throws Exception {
        when(client.fetch()).thenReturn(new GoldApiClient.Quote(cached.usdPerOunce(), clock.instant().plusSeconds(60)));
        service.poll();
        verify(store, never()).save(any(), anyInt());
        assertFalse(service.snapshot(120).lastFetchSucceeded());
    }

    @Test
    void visitorsOnlyReadSnapshotsAndNeverCallTheUpstream() {
        for (int i = 0; i < 10; i++) assertEquals("FRESH", service.snapshot(120).status());
        verifyNoInteractions(client);
        verify(store, never()).save(any(), anyInt());
    }

    @Test
    void unavailableAndDisabledStatesAreExplicit() {
        when(store.recent(anyInt())).thenReturn(List.of());
        assertEquals("UNAVAILABLE", service.snapshot(120).status());
        assertNull(service.snapshot(120).latest());
        var disabled = new LiveGoldService(new LiveGoldProperties(false, "https://example.test", 60, 10, 180, 30), client, store, clock);
        disabled.poll();
        assertEquals("DISABLED", disabled.snapshot(120).status());
        verifyNoInteractions(client);
    }

    @Test
    void metricsScrapingDoesNotReadDatabaseOrCallProvider() {
        service.poll();
        var registry = new SimpleMeterRegistry();
        service.bindTo(registry);
        clearInvocations(client, store);
        assertEquals(cached.observedAt().getEpochSecond(), registry.get("goldcast.live.gold.observed.timestamp").gauge().value());
        assertEquals(1, registry.get("goldcast.live.gold.fetch.success").gauge().value());
        assertEquals(4000.123456, registry.get("goldcast.live.gold.usd.per.ounce").gauge().value());
        verifyNoInteractions(client, store);
    }

    @Test
    void latestAndHistoryHaveConsistentTimestampOrdering() {
        var older = new LiveGoldObservation(new BigDecimal("3990"), clock.instant().minusSeconds(60), clock.instant());
        when(store.recent(anyInt())).thenReturn(List.of(cached, older));
        var result = service.snapshot(120);
        assertEquals(cached.observedAt(), result.latest().observedAt().toInstant());
        assertEquals(older.observedAt(), result.points().getFirst().observedAt().toInstant());
        assertEquals(cached.observedAt(), result.points().getLast().observedAt().toInstant());
    }

    @Test
    void overlappingPollDoesNotDuplicateUpstreamRequests() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.fetch()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return new GoldApiClient.Quote(cached.usdPerOunce(), clock.instant());
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(service::poll);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                service.poll();
                verify(client, times(1)).fetch();
            } finally { release.countDown(); }
            first.get(3, TimeUnit.SECONDS);
        }
    }

    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
