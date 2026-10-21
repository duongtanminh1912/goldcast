package vn.goldcast.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.InstrumentKind;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.ingest.IngestService;
import vn.goldcast.market.PriceSeriesService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoldcastMetricsTest {
    private final PriceSeriesService series = mock(PriceSeriesService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GoldcastMetrics metrics = new GoldcastMetrics(series, mock(IngestService.class),
            mock(AppProperties.class), List.of(), "candidate-42",
            Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));

    private Instrument instrument() {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(1L);
        when(instrument.getCode()).thenReturn("XAUUSD");
        when(instrument.getKind()).thenReturn(InstrumentKind.SPOT_GOLD);
        return instrument;
    }

    private double value(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void weekendLagUsesPublicationDaysAndScrapingDoesNotQueryDatabase() {
        var active = instrument();
        when(series.activeInstruments()).thenReturn(List.of(active));
        PricePoint point = mock(PricePoint.class);
        when(point.getObservedOn()).thenReturn(LocalDate.of(2026, 10, 16));
        when(point.getSource()).thenReturn("synthetic");
        when(series.latest(1L)).thenReturn(Optional.of(point));
        metrics.bindTo(registry);
        metrics.refresh();
        clearInvocations(series);
        assertEquals(1, value("goldcast.market.observation.lag.days"));
        assertEquals(1, value("goldcast.market.observation.synthetic"));
        assertEquals(1, value("goldcast.market.refresh.success"));
        assertEquals(1, registry.get("goldcast.release.info").tag("release_id", "candidate-42").gauge().value());
        verifyNoInteractions(series);
    }

    @Test
    void failedRefreshPreservesLastSuccessTimestampAndSignalsFailure() {
        var active = instrument();
        when(series.activeInstruments()).thenReturn(List.of(active));
        when(series.latest(1L)).thenReturn(Optional.empty());
        metrics.bindTo(registry);
        metrics.refresh();
        double last = value("goldcast.market.refresh.timestamp");
        assertTrue(last > 0);
        assertEquals(0, value("goldcast.market.observation.present"));
        when(series.activeInstruments()).thenThrow(new IllegalStateException("private detail"));
        metrics.refresh();
        assertEquals(0, value("goldcast.market.refresh.success"));
        assertEquals(last, value("goldcast.market.refresh.timestamp"));
    }

    @Test
    void futureObservationIsFlaggedAndInactiveInstrumentLabelsAreRemoved() {
        var active = instrument();
        when(series.activeInstruments()).thenReturn(List.of(active));
        PricePoint point = mock(PricePoint.class);
        when(point.getObservedOn()).thenReturn(LocalDate.of(2026, 10, 20));
        when(series.latest(1L)).thenReturn(Optional.of(point));
        metrics.bindTo(registry);
        metrics.refresh();
        assertEquals(1, value("goldcast.market.observation.future"));
        assertEquals(0, value("goldcast.market.observation.lag.days"));
        when(series.activeInstruments()).thenReturn(List.of());
        metrics.refresh();
        assertNull(registry.find("goldcast.market.observation.present").gauge());
    }
}
