package vn.goldcast.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.repository.InstrumentRepository;
import vn.goldcast.repository.PricePointRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Ingest goi mang ngoai transaction, roi ghi voi mot truy van doc truoc cho moi chuoi. */
class IngestWriteTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final PricePointRepository prices = mock(PricePointRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PriceProvider provider = mock(PriceProvider.class);
    private IngestService service;
    private Instrument gold;
    private PricePoint unchanged;
    private PricePoint revised;

    private static BigDecimal usd(double value) {
        return BigDecimal.valueOf(value).setScale(4);
    }

    @BeforeEach
    void setup() {
        gold = mock(Instrument.class);
        when(gold.getId()).thenReturn(1L);
        when(gold.getCode()).thenReturn("XAUUSD");
        when(gold.getSource()).thenReturn("stooq");
        when(instruments.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(gold));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(provider.id()).thenReturn("stooq");
        when(provider.enabled()).thenReturn(true);

        unchanged = new PricePoint(gold, DAY.minusDays(2), usd(2650), null, null, "stooq");
        revised = new PricePoint(gold, DAY.minusDays(1), usd(2660), null, null, "stooq");
        when(prices.findSince(eq(1L), any())).thenReturn(new ArrayList<>(List.of(unchanged, revised)));

        // Ba dong: mot da luu va khong doi, mot da luu nhung bi sua gia, mot ngay moi.
        List<ProviderQuote> quotes = new ArrayList<>();
        quotes.add(new ProviderQuote("XAUUSD", DAY.minusDays(2), usd(2650), null, null));
        quotes.add(new ProviderQuote("XAUUSD", DAY.minusDays(1), usd(2671.5), null, null));
        quotes.add(new ProviderQuote("XAUUSD", DAY, usd(2680), null, null));
        when(provider.fetch(any(), anyInt())).thenReturn(quotes);

        AppProperties properties = new AppProperties(null,
                new AppProperties.Ingest(true, false, "0 7 * * * *", false, 1200, 20), null, null);
        service = new IngestService(instruments, prices, List.of(provider),
                mock(SyntheticSeedGenerator.class), properties, transactions, null);
    }

    @Test
    void providersAreFetchedBeforeAnyTransactionOpens() {
        service.run();
        InOrder order = inOrder(provider, transactions, prices);
        order.verify(provider).fetch(any(), anyInt());
        order.verify(transactions).getTransaction(any());
        order.verify(prices).findSince(eq(1L), any());
        order.verify(transactions).commit(any());
    }

    @Test
    void storedRowsAreLoadedOnceAndOnlyRealChangesAreWritten() {
        IngestReport report = service.run();

        verify(prices, times(1)).findSince(eq(1L), eq(DAY.minusDays(2)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PricePoint>> saved = ArgumentCaptor.forClass((Class) Iterable.class);
        verify(prices).saveAll(saved.capture());
        List<PricePoint> inserted = new ArrayList<>();
        saved.getValue().forEach(inserted::add);
        assertEquals(1, inserted.size());
        assertEquals(DAY, inserted.get(0).getObservedOn());

        assertEquals(0, usd(2671.5).compareTo(revised.getClosePrice()), "revision applied to the managed row");
        assertEquals(0, usd(2650).compareTo(unchanged.getClosePrice()));
        var outcome = report.providers().get(0);
        assertEquals(3, outcome.quotes());
        assertEquals(1, outcome.inserted());
        assertEquals(1, outcome.updated());
        verifyNoMoreInteractions(prices);
    }

    @Test
    void aSecondQuoteForTheSameNewDayUpdatesInsteadOfInsertingTwice() {
        when(provider.fetch(any(), anyInt())).thenReturn(List.of(
                new ProviderQuote("XAUUSD", DAY, usd(2680), null, null),
                new ProviderQuote("XAUUSD", DAY, usd(2685), null, null)));
        IngestReport report = service.run();
        var outcome = report.providers().get(0);
        assertEquals(1, outcome.inserted());
        assertEquals(1, outcome.updated());
    }

    @Test
    void aFailedWriteRollsBackAndReleasesTheGuard() {
        when(prices.saveAll(any())).thenThrow(new IllegalStateException("constraint"));
        assertThrows(IllegalStateException.class, service::run);
        verify(transactions).rollback(any());
        assertTrue(service.status().lastTransactionFailed());
        reset(prices);
        when(prices.findSince(eq(1L), any())).thenReturn(new ArrayList<>());
        assertNotNull(service.run());
    }
}
