package vn.goldcast.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.goldcast.api.AdminController;
import vn.goldcast.api.ApiExceptionHandler;
import vn.goldcast.config.AppProperties;
import vn.goldcast.repository.InstrumentRepository;
import vn.goldcast.repository.PricePointRepository;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IngestConcurrencyTest {
    private InstrumentRepository instruments;
    private PlatformTransactionManager transactions;
    private IngestService service;
    private AppProperties properties;

    @BeforeEach
    void setup() {
        instruments = mock(InstrumentRepository.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(instruments.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of());
        properties = new AppProperties(null,
                new AppProperties.Ingest(true, false, "0 7 * * * *", true, 1200, 20), null, null);
        service = new IngestService(instruments, mock(PricePointRepository.class), List.of(),
                mock(SyntheticSeedGenerator.class), properties, transactions);
    }

    @Test
    void manualAndScheduledCallsShareOneGuard() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(instruments.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return List.of();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(service::run);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IngestAlreadyRunningException.class,
                        () -> new AdminController(service, List.of()).ingest());
                assertDoesNotThrow(() -> new IngestScheduler(service, properties).onSchedule());
                verify(transactions, times(1)).getTransaction(any());
                verify(instruments, times(1)).findByActiveTrueOrderBySortOrderAscCodeAsc();
            } finally {
                finish.countDown();
            }
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        }
        assertNotNull(service.run());
    }

    @Test
    void guardRemainsHeldUntilCommitFinishes() {
        doAnswer(call -> {
            assertThrows(IngestAlreadyRunningException.class, service::run);
            return null;
        }).when(transactions).commit(any());
        assertNotNull(service.run());
        assertNotNull(service.run());
    }

    @Test
    void failedTransactionReleasesGuardAfterRollback() {
        when(instruments.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .thenThrow(new IllegalStateException("database error")).thenReturn(List.of());
        assertThrows(IllegalStateException.class, service::run);
        verify(transactions).rollback(any());
        assertNotNull(service.run());
    }

    @Test
    void failedCommitDoesNotLeaveIngestPermanentlyLocked() {
        doThrow(new TransactionSystemException("commit failed")).doNothing().when(transactions).commit(any());
        assertThrows(TransactionSystemException.class, service::run);
        assertNotNull(service.run());
    }

    @Test
    void busyManualRequestMapsToHttp409() {
        var problem = new ApiExceptionHandler().ingestInProgress(new IngestAlreadyRunningException());
        assertEquals(409, problem.getStatus());
    }
}
