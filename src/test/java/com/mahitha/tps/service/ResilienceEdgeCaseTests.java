package com.mahitha.tps.service;

import com.mahitha.tps.config.ProcessingProperties;
import com.mahitha.tps.dto.*;
import com.mahitha.tps.exception.ApiException;
import com.mahitha.tps.model.*;
import com.mahitha.tps.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:resilience-tests;DB_CLOSE_DELAY=-1", "processing.enabled=false"})
class ResilienceEdgeCaseTests {
    @Autowired TransactionService service;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcessingProperties properties;
    @MockitoSpyBean FailureSimulator simulator;
    @MockitoSpyBean TransactionRepository repository;
    @MockitoBean Clock clock;
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach void prepare() {
        when(clock.instant()).thenReturn(now);
        jdbc.update("DELETE FROM processing_attempts");
        jdbc.update("DELETE FROM ledger"); jdbc.update("DELETE FROM request_keys");
        jdbc.update("DELETE FROM transactions"); jdbc.update("DELETE FROM accounts");
        properties.setMaxAttempts(3); properties.setRetryDelayMs(1000);
        properties.getSimulatedFailures().clear();
        accounts.create(new CreateAccountRequest("A", "USD", BigDecimal.ZERO, null));
        accounts.create(new CreateAccountRequest("B", "USD", BigDecimal.ZERO, null));
    }
    private TransactionRequest request(String id, String account, long sequence, TransactionType type) {
        return new TransactionRequest(id, "R-" + id, account, type, BigDecimal.TEN, "USD", sequence);
    }
    private TransactionRequest credit(String id, String account, long sequence) { return request(id, account, sequence, TransactionType.CREDIT); }
    private void failDebit() {
        service.submit(request("BAD", "A", 1, TransactionType.DEBIT));
        service.processBatch();
        assertThat(service.get("BAD").status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test void unexpectedFailureRollsBackAndDoesNotStopOtherAccounts() {
        doAnswer(invocation -> {
            TransactionRecord r = invocation.getArgument(0);
            if (r.transactionId().equals("BAD")) throw new IllegalStateException("Internal detail must not escape");
            return null;
        }).when(simulator).afterBalanceUpdate(any());
        service.submit(credit("BAD", "A", 1));
        service.submit(credit("GOOD", "B", 1));
        service.processBatch();
        assertThat(service.get("BAD").status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(service.get("BAD").errorCode()).isEqualTo("NON_RETRYABLE_PROCESSING_FAILURE");
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("0");
        assertThat(accounts.get("A", "USD").nextSequence()).isEqualTo(1);
        assertThat(service.get("GOOD").status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class)).isEqualTo(1);
        doNothing().when(simulator).afterBalanceUpdate(any());
        service.retry("BAD"); service.processBatch();
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("10");
    }

    @Test void retryBackoffAndDuplicatesDoNotConsumeAttemptsPrematurely() {
        properties.getSimulatedFailures().put("T", 2);
        service.submit(credit("T", "A", 1)); service.processBatch();
        assertThat(service.get("T").nextAttemptAt()).isEqualTo(now.plusSeconds(1));
        assertThat(service.submit(credit("T", "A", 1)).outcome()).isEqualTo("DUPLICATE");
        service.processBatch();
        assertThat(service.get("T").attempts()).isEqualTo(1);
        when(clock.instant()).thenReturn(now.plusSeconds(1)); service.processBatch();
        assertThat(service.get("T").nextAttemptAt()).isEqualTo(now.plusSeconds(3));
        when(clock.instant()).thenReturn(now.plusSeconds(2)); service.processBatch();
        assertThat(service.get("T").attempts()).isEqualTo(2);
        when(clock.instant()).thenReturn(now.plusSeconds(3)); service.processBatch();
        assertThat(service.get("T").status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(service.get("T").attempts()).isEqualTo(3);
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("10");
    }

    @Test void cancellationIsAuditedIdempotentAndUnblocksSuccessorWithoutLedgerEffect() {
        failDebit(); service.submit(credit("NEXT", "A", 2));
        service.cancel("BAD", new CancelTransactionRequest("CUSTOMER_WITHDREW"));
        service.cancel("BAD", new CancelTransactionRequest("CUSTOMER_WITHDREW"));
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("0");
        assertThat(accounts.get("A", "USD").nextSequence()).isEqualTo(2);
        assertThat(service.attempts("BAD", 100, 0)).extracting(row -> row.get("OUTCOME")).containsExactly("FAILED", "CANCELLED");
        assertThat(service.submit(request("BAD", "A", 1, TransactionType.DEBIT)).outcome()).isEqualTo("DUPLICATE");
        assertThatThrownBy(() -> service.retry("BAD")).isInstanceOf(ApiException.class);
        service.processBatch();
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class)).isEqualTo(1);
        assertThat((List<?>) service.summary().get("blockedStreams")).isEmpty();
    }

    @Test void cancellationCannotSkipPendingOrProcessedTransactions() {
        service.submit(credit("GAP", "A", 2)); service.processBatch();
        assertThatThrownBy(() -> service.cancel("GAP", new CancelTransactionRequest("RECONCILED"))).isInstanceOf(ApiException.class);
        service.submit(credit("FIRST", "A", 1)); service.processBatch(); service.processBatch();
        assertThatThrownBy(() -> service.cancel("FIRST", new CancelTransactionRequest("RECONCILED"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.cancel("GAP", new CancelTransactionRequest(""))).isInstanceOf(ApiException.class);
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("20");
    }

    @Test void concurrentRetryAndCancellationHaveOnlyOneWinner() throws Exception {
        failDebit();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> retry = () -> { gate.await(); try { service.retry("BAD"); return true; } catch (ApiException e) { return false; } };
            Callable<Boolean> cancel = () -> { gate.await(); try { service.cancel("BAD", new CancelTransactionRequest("RECONCILED")); return true; } catch (ApiException e) { return false; } };
            var a = pool.submit(retry); var b = pool.submit(cancel); gate.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class)).isZero();
    }

    @Test void staleFailureCannotConsumeNewRetryBudgetAfterRecovery() throws Exception {
        properties.setMaxAttempts(1);
        service.submit(credit("T", "A", 1));
        var delayed = new AtomicBoolean(false);
        var waiting = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("stale-worker")) delayed.set(true);
            throw new FailureSimulator.SimulatedTransientException();
        }).when(simulator).afterBalanceUpdate(any());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("stale-worker") && delayed.compareAndSet(true, false)) {
                waiting.countDown();
                if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test synchronization timed out");
            }
            return invocation.callRealMethod();
        }).when(repository).lock();
        try (var pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "stale-worker"))) {
            var worker = pool.submit(service::processBatch);
            try {
                assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
                service.processBatch(); // Another worker fails and exhausts the original budget.
                assertThat(service.get("T").status()).isEqualTo(TransactionStatus.FAILED);
                service.retry("T"); // Same attempt count as the old snapshot, but a different version.
            } finally { resume.countDown(); }
            worker.get(5, TimeUnit.SECONDS);
        }
        assertThat(service.get("T").status()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(service.get("T").attempts()).isZero();
        assertThat(service.attempts("T", 100, 0)).hasSize(2);
    }

    @Test void cancellationRollsBackIfAuditWriteFails() {
        failDebit();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
                .when(repository).attempt(eq("BAD"), anyInt(), eq("CANCELLED"), anyString(), any());
        assertThatThrownBy(() -> service.cancel("BAD", new CancelTransactionRequest("RECONCILED")))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(service.get("BAD").status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(accounts.get("A", "USD").nextSequence()).isEqualTo(1);
        assertThat(service.attempts("BAD", 100, 0)).hasSize(1);
    }

    @Test void currenciesHaveIndependentBalancesAndSequences() {
        accounts.create(new CreateAccountRequest("A", "INR", BigDecimal.ZERO, null));
        service.submit(credit("USD", "A", 1));
        service.submit(new TransactionRequest("INR", "R-INR", "A", TransactionType.CREDIT, new BigDecimal("25"), "INR", 1L));
        service.processBatch();
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("10");
        assertThat(accounts.get("A", "INR").balance()).isEqualByComparingTo("25");
        assertThat(accounts.get("A", "USD").nextSequence()).isEqualTo(2);
        assertThat(accounts.get("A", "INR").nextSequence()).isEqualTo(2);
    }

    @Test void suppliedSixRecordExampleHasExpectedOutcomesAndBalances() throws Exception {
        CreateAccountRequest[] setup = mapper.readValue(new java.io.File("examples/accounts.json"), CreateAccountRequest[].class);
        for (CreateAccountRequest account : setup) accounts.create(account);
        TransactionRequest[] input = mapper.readValue(new java.io.File("examples/transactions.json"), TransactionRequest[].class);
        properties.getSimulatedFailures().put("TXN-2005", 1);
        var results = service.submitBatch(java.util.Arrays.asList(input));
        assertThat(results).extracting(SubmissionResult::outcome)
                .containsExactly("ACCEPTED", "ACCEPTED", "DUPLICATE", "ACCEPTED", "INVALID", "ACCEPTED");
        service.processBatch();
        when(clock.instant()).thenReturn(now.plusSeconds(1));
        service.processBatch(); service.processBatch();
        assertThat(accounts.get("ACC-201", "USD").balance()).isEqualByComparingTo("85");
        assertThat(accounts.get("ACC-203", "USD").balance()).isEqualByComparingTo("500");
        assertThat(service.get("TXN-2005").attempts()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class)).isEqualTo(4);
        assertThat((List<?>) service.summary().get("blockedStreams")).isEmpty();
        service.submitBatch(java.util.Arrays.asList(input)); service.processBatch();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class)).isEqualTo(4);
    }

    @Test void failureBookkeepingErrorDoesNotStopOtherAccounts() {
        doAnswer(invocation -> {
            TransactionRecord r = invocation.getArgument(0);
            if (r.transactionId().equals("BAD")) throw new FailureSimulator.SimulatedTransientException();
            return null;
        }).when(simulator).afterBalanceUpdate(any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
                .when(repository).attempt(eq("BAD"), anyInt(), anyString(), any(), any());
        service.submit(credit("BAD", "A", 1)); service.submit(credit("GOOD", "B", 1));
        service.processBatch();
        assertThat(service.get("BAD").status()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(accounts.get("A", "USD").balance()).isEqualByComparingTo("0");
        assertThat(service.get("GOOD").status()).isEqualTo(TransactionStatus.PROCESSED);
    }
}
