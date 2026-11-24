package vn.goldcast.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/** Luu forecast run la de kiem toan; luu hong khong bao gio duoc lam hong cau tra loi. */
class ForecastPersistTest {
    private final PriceSeriesService series = mock(PriceSeriesService.class);
    private final ForecastRunRepository runs = mock(ForecastRunRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private ForecastService service;

    @BeforeEach
    void setup() {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(7L);
        when(instrument.getCode()).thenReturn("USDVND");
        when(instrument.getKind()).thenReturn(InstrumentKind.FX);
        when(instrument.getUnit()).thenReturn(PriceUnit.VND_PER_USD);
        List<PricePoint> history = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < 80; i++, day = day.plusDays(1)) {
            BigDecimal close = BigDecimal.valueOf(25_000 + 3 * i + (i % 5));
            history.add(new PricePoint(instrument, day, close, null, null, "test"));
        }
        when(series.requireInstrument("USDVND")).thenReturn(instrument);
        when(series.recent(eq(7L), anyInt())).thenReturn(history);
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
        verify(transactions).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
        verify(runs).saveAndFlush(any());
        verify(transactions).commit(any());
    }

    @Test
    void failedWriteStillAnswersAndRollsBackOnlyTheAuditTransaction() {
        when(runs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        var dto = assertDoesNotThrow(() -> service.forecast("USDVND", ForecastModel.NAIVE, 7, true));
        assertEquals(7, dto.points().size());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void failureAtCommitStillAnswers() {
        doThrow(new TransactionSystemException("commit failed")).when(transactions).commit(any());
        var dto = assertDoesNotThrow(() -> service.forecast("USDVND", ForecastModel.NAIVE, 7, true));
        assertEquals(7, dto.points().size());
    }

    @Test
    void withoutPersistNothingIsWritten() {
        service.forecast("USDVND", ForecastModel.NAIVE, 7, false);
        verifyNoInteractions(runs, transactions);
    }
}
