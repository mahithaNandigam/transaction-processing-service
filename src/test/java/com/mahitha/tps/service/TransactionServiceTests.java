package com.mahitha.tps.service;

import com.mahitha.tps.config.ProcessingProperties;
import com.mahitha.tps.dto.TransactionRequest;
import com.mahitha.tps.dto.CreateAccountRequest;
import com.mahitha.tps.exception.ApiException;
import com.mahitha.tps.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:service-tests;DB_CLOSE_DELAY=-1", "processing.enabled=false", "processing.retry-delay-ms=1"})
class TransactionServiceTests {
    @Autowired TransactionService service;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcessingProperties properties;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM processing_attempts");
        jdbc.update("DELETE FROM ledger"); jdbc.update("DELETE FROM request_keys");
        jdbc.update("DELETE FROM transactions"); jdbc.update("DELETE FROM accounts");
        properties.getSimulatedFailures().clear();
        accounts.create(new CreateAccountRequest("ACC-201", "USD", BigDecimal.ZERO, null));
    }
    private TransactionRequest request(String id, String req, long sequence, TransactionType type, String amount) {
        return new TransactionRequest(id, req, "ACC-201", type, new BigDecimal(amount), "USD", sequence);
    }
    private TransactionRequest credit(String id, long seq) { return request(id, "R-" + id, seq, TransactionType.CREDIT, "100"); }
    private void balance(String expected, long entries) {
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE account_id='ACC-201' AND currency='USD'", BigDecimal.class)).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class)).isEqualTo(entries);
    }
    @Test void duplicateBeforeAndAfterProcessingHasOnlyOneEffect() {
        service.submit(credit("T1", 1));
        assertThat(service.submit(request("T1", "R2", 1, TransactionType.CREDIT, "100.00")).outcome()).isEqualTo("DUPLICATE");
        service.processBatch();
        assertThat(service.submit(credit("T1", 1)).outcome()).isEqualTo("DUPLICATE");
        service.processBatch(); balance("100", 1);
    }
    @Test void conflictingBusinessRequestAndSequenceKeysAreRejected() {
        service.submit(credit("T1", 1));
        assertThatThrownBy(() -> service.submit(request("T1", "OTHER", 1, TransactionType.CREDIT, "101"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit(request("T2", "R-T1", 2, TransactionType.CREDIT, "100"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit(credit("T2", 1))).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isEqualTo(1);
    }
    @Test void outOfOrderDebitWaitsForCredit() {
        service.submit(request("T2", "R2", 2, TransactionType.DEBIT, "40"));
        service.processBatch();
        assertThat(service.get("T2").status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(service.summary().get("blockedStreams").toString()).contains("EXPECTED_SEQUENCE=1");
        service.submit(credit("T1", 1)); service.processBatch(); service.processBatch();
        balance("60", 2);
        assertThat(service.get("T2").status()).isEqualTo(TransactionStatus.PROCESSED);
    }
    @Test void transientFailureRollsBackLedgerBalanceAndSequenceThenRetries() throws Exception {
        properties.getSimulatedFailures().put("T1", 1);
        service.submit(credit("T1", 1)); service.processBatch();
        balance("0", 0);
        assertThat(service.get("T1").status()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(jdbc.queryForObject("SELECT next_sequence FROM accounts", Long.class)).isEqualTo(1);
        Thread.sleep(10); service.processBatch(); balance("100", 1);
        assertThat(service.get("T1").attempts()).isEqualTo(2);
    }
    @Test void retryExhaustionCanBeRecoveredAfterCauseIsRemoved() throws Exception {
        properties.getSimulatedFailures().put("T1", 10);
        service.submit(credit("T1", 1));
        for (int i=0; i<3; i++) { service.processBatch(); Thread.sleep(10); }
        assertThat(service.get("T1").status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(service.get("T1").attempts()).isEqualTo(3); balance("0", 0);
        properties.getSimulatedFailures().clear();
        service.retry("T1"); service.processBatch(); balance("100", 1);
        assertThatThrownBy(() -> service.retry("T1")).isInstanceOf(ApiException.class);
    }
    @Test void invalidBatchRecordDoesNotBlockValidRecords() {
        var results = service.submitBatch(Arrays.asList(credit("T1", 1), request("BAD", "RB", 2, TransactionType.CREDIT, "-10"), null, credit("T2", 2)));
        assertThat(results).extracting(r -> r.outcome()).containsExactly("ACCEPTED", "INVALID", "INVALID", "ACCEPTED");
        service.processBatch(); balance("200", 2);
    }
    @Test void insufficientFundsFailsAndBlocksLaterSequence() {
        service.submit(request("T1", "R1", 1, TransactionType.DEBIT, "40"));
        service.submit(credit("T2", 2)); service.processBatch();
        assertThat(service.get("T1").errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(service.get("T2").status()).isEqualTo(TransactionStatus.PENDING); balance("0", 0);
    }
    @Test void concurrentSubmissionsAndWorkersDoNotDuplicateEffects() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> submissions = new ArrayList<>();
            for (int i=0; i<20; i++) {
                int index = i;
                submissions.add(() -> service.submit(request("T1", "R" + index, 1, TransactionType.CREDIT, "100")).outcome());
            }
            var results = pool.invokeAll(submissions);
            int accepted = 0;
            for (var result : results) if (result.get().equals("ACCEPTED")) accepted++;
            assertThat(accepted).isEqualTo(1);
            List<Callable<Void>> workers = new ArrayList<>();
            for (int i=0; i<8; i++) workers.add(() -> { service.processBatch(); return null; });
            for (var worker : pool.invokeAll(workers)) worker.get();
        }
        balance("100", 1);
    }
    @Test void unknownAccountAndCurrencyAreRejectedWithoutCreatingTransactions() {
        assertThatThrownBy(() -> service.submit(new TransactionRequest("T1", "R1", "UNKNOWN", TransactionType.CREDIT, BigDecimal.TEN, "USD", 1L)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit(new TransactionRequest("T1", "R1", "ACC-201", TransactionType.CREDIT, BigDecimal.TEN, "INR", 1L)))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isZero();
    }
    @Test void openingBalanceAndExplicitStartingSequenceAreRespected() {
        accounts.create(new CreateAccountRequest("START", "INR", new BigDecimal("50"), 5L));
        assertThatThrownBy(() -> service.submit(new TransactionRequest("OLD", "ROLD", "START", TransactionType.DEBIT, BigDecimal.TEN, "INR", 4L)))
                .isInstanceOf(ApiException.class);
        service.submit(new TransactionRequest("T5", "R5", "START", TransactionType.DEBIT, BigDecimal.TEN, "INR", 5L));
        service.processBatch();
        assertThat(accounts.get("START", "INR").balance()).isEqualByComparingTo("40");
        assertThat(accounts.get("START", "INR").nextSequence()).isEqualTo(6);
        assertThatThrownBy(() -> accounts.create(new CreateAccountRequest("START", "INR", BigDecimal.ZERO, 1L))).isInstanceOf(ApiException.class);
        assertThat(accounts.get("START", "INR").balance()).isEqualByComparingTo("40");
    }
    @Test void requestOutcomesAndReplayCountsArePersistedSeparatelyFromBusinessStatus() {
        service.submit(credit("T1", 1));
        service.submit(credit("T1", 1));
        service.submit(request("T1", "DUP", 1, TransactionType.CREDIT, "100"));
        service.processBatch();
        assertThat(service.request("R-T1")).containsEntry("outcome", "ACCEPTED").containsEntry("replayCount", 1L);
        assertThat(service.request("DUP")).containsEntry("outcome", "DUPLICATE");
        assertThat(service.get("T1").status()).isEqualTo(TransactionStatus.PROCESSED);
    }
    @Test void recoveryPreservesAttemptHistory() throws Exception {
        properties.getSimulatedFailures().put("T1", 5);
        service.submit(credit("T1", 1));
        for (int i=0; i<3; i++) { service.processBatch(); Thread.sleep(10); }
        properties.getSimulatedFailures().clear();
        service.retry("T1"); service.processBatch();
        assertThat(service.attempts("T1", 100, 0)).extracting(row -> row.get("OUTCOME"))
                .containsExactly("RETRY_PENDING", "RETRY_PENDING", "FAILED", "MANUAL_RECOVERY", "PROCESSED");
        balance("100", 1);
    }
    @Test void blockedAccountsDoNotStarveReadyAccounts() {
        properties.setBatchSize(1);
        try {
            service.submit(credit("GAP", 2));
            accounts.create(new CreateAccountRequest("OTHER", "USD", BigDecimal.ZERO, null));
            service.submit(new TransactionRequest("READY", "RREADY", "OTHER", TransactionType.CREDIT, BigDecimal.TEN, "USD", 1L));
            service.processBatch();
            assertThat(service.get("READY").status()).isEqualTo(TransactionStatus.PROCESSED);
        } finally { properties.setBatchSize(100); }
    }
}
