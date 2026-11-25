package vn.goldcast.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.InstrumentKind;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.domain.PriceUnit;
import vn.goldcast.macro.MacroProperties;
import vn.goldcast.macro.MacroStore;
import vn.goldcast.market.PriceSeriesService;
import vn.goldcast.repository.ForecastRunRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Doc DB trong mot transaction chi doc ngan, tinh backtest sau khi da tra connection, va
 * luu forecast run trong transaction rieng: luu hong khong bao gio duoc lam hong cau tra loi.
 */
class ForecastPersistTest {
    private final PriceSeriesService series = mock(PriceSeriesService.class);
    private final ForecastRunRepository runs = mock(ForecastRunRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private ForecastService service;
    private List<PricePoint> history;
    private Instrument instrument;

    @BeforeEach
    void setup() {
        instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(7L);
        when(instrument.getCode()).thenReturn("USDVND");
        when(instrument.getKind()).thenReturn(InstrumentKind.FX);
        when(instrument.getUnit()).thenReturn(PriceUnit.VND_PER_USD);
        history = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < 80; i++, day = day.plusDays(1)) {
            BigDecimal close = BigDecimal.valueOf(25_000 + 3 * i + (i % 5));
            history.add(new PricePoint(instrument, day, close, null, null, "test"));
        }
        when(series.requireInstrument("USDVND")).thenReturn(instrument);
        when(series.recent(eq(7L), anyInt())).thenAnswer(call -> history);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        AppProperties properties = new AppProperties(null, null, null,
                new AppProperties.Forecast(7, 30, 30, 20, 10, 750));
        service = new ForecastService(series, runs, properties, new ObjectMapper(), mock(MacroStore.class),
                new MacroProperties(false, "https://fred.example/csv", 1200, 7), transactions);
    }

    @Test
    void runIsWrittenAndCommittedInItsOwnTransaction() {
        var dto = service.forecast("USDVND", ForecastModel.NAIVE, 7, true);
        assertEquals(7, dto.points().size());
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definition.capture());
        TransactionDefinition read = definition.getAllValues().get(0);
        TransactionDefinition audit = definition.getAllValues().get(1);
        assertTrue(read.isReadOnly());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, audit.getPropagationBehavior());
        assertFalse(audit.isReadOnly());
        verify(runs).saveAndFlush(any());
        verify(transactions, times(2)).commit(any());
    }

    @Test
    void failedWriteStillAnswersAndRollsBackOnlyTheAuditTransaction() {
        when(runs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        var dto = assertDoesNotThrow(() -> service.forecast("USDVND", ForecastModel.NAIVE, 7, true));
        assertEquals(7, dto.points().size());
        verify(transactions).rollback(any());
        verify(transactions, times(1)).commit(any()); // chi transaction doc
    }

    @Test
    void failureAtCommitStillAnswers() {
        // Lan commit thu nhat la cua transaction doc; chi lan thu hai, cua ban ghi kiem toan, hong.
        doNothing().doThrow(new TransactionSystemException("commit failed")).when(transactions).commit(any());
        var dto = assertDoesNotThrow(() -> service.forecast("USDVND", ForecastModel.NAIVE, 7, true));
        assertEquals(7, dto.points().size());
    }

    @Test
    void withoutPersistOnlyTheReadTransactionRuns() {
        service.forecast("USDVND", ForecastModel.NAIVE, 7, false);
        verifyNoInteractions(runs);
        verify(transactions, times(1)).getTransaction(argThat(TransactionDefinition::isReadOnly));
    }

    @Test
    void theReadTransactionIsClosedBeforeAnyRunIsStored() {
        service.forecast("USDVND", ForecastModel.AUTO, 7, true);
        InOrder order = inOrder(series, transactions, runs);
        order.verify(transactions).getTransaction(argThat(TransactionDefinition::isReadOnly));
        order.verify(series).recent(eq(7L), anyInt());
        order.verify(transactions).commit(any());
        order.verify(transactions).getTransaction(argThat(d -> !d.isReadOnly()));
        order.verify(runs).saveAndFlush(any());
    }

    @Test
    void sameInputsReuseTheBacktestAndAnyChangedQuoteRecomputesIt() {
        var first = service.forecast("USDVND", ForecastModel.AUTO, 7, false);
        long computed = service.cacheMisses();
        assertTrue(computed >= 5, "AUTO backtests every candidate: " + computed);

        var again = service.forecast("USDVND", ForecastModel.AUTO, 7, false);
        assertEquals(computed, service.cacheMisses(), "nothing recomputed");
        assertEquals(computed, service.cacheHits());
        assertEquals(first.model(), again.model());
        assertEquals(first.accuracy(), again.accuracy());

        // A dealer revises one past quote: every backtest must see the new value.
        PricePoint revised = new PricePoint(instrument, history.get(40).getObservedOn(),
                history.get(40).getClosePrice().add(BigDecimal.valueOf(500)), null, null, "test");
        history.set(40, revised);
        service.forecast("USDVND", ForecastModel.AUTO, 7, false);
        assertEquals(2 * computed, service.cacheMisses());
    }

    @Test
    void anotherHorizonIsAnotherBacktest() {
        service.forecast("USDVND", ForecastModel.NAIVE, 7, false);
        service.forecast("USDVND", ForecastModel.NAIVE, 14, false);
        assertEquals(2, service.cacheMisses());
    }
}
