package com.mahitha.tps.repository;

import com.mahitha.tps.dto.TransactionRequest;
import com.mahitha.tps.dto.CreateAccountRequest;
import com.mahitha.tps.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Repository
public class TransactionRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<TransactionRecord> MAPPER = (rs, n) -> new TransactionRecord(
            rs.getString("transaction_id"), rs.getString("account_id"),
            TransactionType.valueOf(rs.getString("transaction_type")), rs.getBigDecimal("amount"),
            rs.getString("currency"), rs.getLong("sequence_number"),
            TransactionStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
            rs.getString("error_code"), instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
            instant(rs.getObject("created_at", OffsetDateTime.class)), instant(rs.getObject("updated_at", OffsetDateTime.class)));
    public TransactionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static OffsetDateTime time(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }

    // Every writer takes this database lock inside a transaction. It also protects absent keys.
    public void lock() { jdbc.queryForObject("SELECT id FROM processing_lock WHERE id=1 FOR UPDATE", Integer.class); }
    public Optional<TransactionRecord> find(String id) {
        return jdbc.query("SELECT * FROM transactions WHERE transaction_id=?", MAPPER, id).stream().findFirst();
    }
    public Optional<String> requestOwner(String id) {
        return jdbc.queryForList("SELECT transaction_id FROM request_keys WHERE request_id=?", String.class, id).stream().findFirst();
    }
    public void addRequest(String requestId, String transactionId, String outcome) {
        jdbc.update("INSERT INTO request_keys(request_id,transaction_id,outcome) VALUES (?,?,?)", requestId, transactionId, outcome);
    }
    public void replayRequest(String requestId) {
        jdbc.update("UPDATE request_keys SET replay_count=replay_count+1 WHERE request_id=?", requestId);
    }
    public Map<String, Object> request(String id) {
        return jdbc.queryForList("SELECT request_id AS \"requestId\",transaction_id AS \"transactionId\",outcome AS \"outcome\",replay_count AS \"replayCount\",received_at AS \"receivedAt\" FROM request_keys WHERE request_id=?", id).stream().findFirst().orElse(null);
    }
    public List<Map<String, Object>> requestCounts() {
        return jdbc.queryForList("SELECT outcome,COUNT(*) AS total,SUM(replay_count) AS replays FROM request_keys GROUP BY outcome");
    }
    public void createAccount(CreateAccountRequest request) {
        jdbc.update("INSERT INTO accounts(account_id,currency,balance,opening_balance,next_sequence,starting_sequence) VALUES (?,?,?,?,?,?)",
                request.accountId(), request.currency(), request.openingBalance(), request.openingBalance(), request.initialSequence(), request.initialSequence());
    }
    public List<Account> accounts(String id, String currency) {
        return jdbc.query("SELECT * FROM accounts WHERE account_id=?" + (currency == null ? "" : " AND currency=?"),
                (rs, n) -> new Account(rs.getString("account_id"), rs.getString("currency"), rs.getBigDecimal("opening_balance"),
                        rs.getBigDecimal("balance"), rs.getLong("starting_sequence"), rs.getLong("next_sequence")),
                currency == null ? new Object[]{id} : new Object[]{id, currency});
    }
    public void attempt(String id, int number, String outcome, String error, Instant now) {
        jdbc.update("INSERT INTO processing_attempts(transaction_id,attempt_number,outcome,error_code,recorded_at) VALUES (?,?,?,?,?)",
                id, number, outcome, error, time(now));
    }
    public List<Map<String, Object>> attempts(String id, int limit, long offset) {
        return jdbc.queryForList("SELECT * FROM processing_attempts WHERE transaction_id=? ORDER BY id LIMIT ? OFFSET ?", id, limit, offset);
    }
    public boolean sequenceOccupied(TransactionRequest r) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id=? AND currency=? AND sequence_number=?",
                Long.class, r.accountId(), r.currency(), r.sequenceNumber()) > 0;
    }
    public void insert(TransactionRequest r, Instant now) {
        jdbc.update("INSERT INTO transactions(transaction_id,account_id,transaction_type,amount,currency,sequence_number,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'RECEIVED',?,?)",
                r.transactionId(), r.accountId(), r.transactionType().name(), r.amount(), r.currency(), r.sequenceNumber(), time(now), time(now));
        addRequest(r.requestId(), r.transactionId(), "ACCEPTED");
    }
    public List<TransactionRecord> candidates(Instant now, int limit) {
        return jdbc.query("SELECT t.* FROM transactions t JOIN accounts a ON t.account_id=a.account_id AND t.currency=a.currency WHERE t.status IN ('RECEIVED','PENDING','RETRY_PENDING') AND (t.next_attempt_at IS NULL OR t.next_attempt_at<=?) ORDER BY CASE WHEN t.sequence_number=a.next_sequence THEN 0 ELSE 1 END,t.sequence_number,t.created_at LIMIT ?",
                MAPPER, time(now), limit);
    }
    public long nextSequence(TransactionRecord r) {
        return jdbc.queryForObject("SELECT next_sequence FROM accounts WHERE account_id=? AND currency=?", Long.class, r.accountId(), r.currency());
    }
    public BigDecimal balance(TransactionRecord r) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE account_id=? AND currency=?", BigDecimal.class, r.accountId(), r.currency());
    }
    public void state(String id, TransactionStatus status, int attempts, String error, Instant next, Instant now) {
        jdbc.update("UPDATE transactions SET status=?,attempts=?,error_code=?,next_attempt_at=?,updated_at=? WHERE transaction_id=?",
                status.name(), attempts, error, time(next), time(now), id);
    }
    public void apply(TransactionRecord r, BigDecimal delta, BigDecimal balance, Instant now) {
        jdbc.update("INSERT INTO ledger(transaction_id,account_id,currency,delta,balance_after,created_at) VALUES (?,?,?,?,?,?)",
                r.transactionId(), r.accountId(), r.currency(), delta, balance, time(now));
        jdbc.update("UPDATE accounts SET balance=?,next_sequence=next_sequence+1 WHERE account_id=? AND currency=?",
                balance, r.accountId(), r.currency());
    }
    public Map<String, Object> account(String id, String currency) {
        return jdbc.queryForList("SELECT account_id,currency,balance,next_sequence FROM accounts WHERE account_id=? AND currency=?", id, currency)
                .stream().findFirst().orElse(null);
    }
    public List<Map<String, Object>> ledger(String id, String currency, int limit, long offset) {
        return jdbc.queryForList("SELECT * FROM ledger WHERE account_id=? AND currency=? ORDER BY created_at,transaction_id LIMIT ? OFFSET ?", id, currency, limit, offset);
    }
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) counts.put(status.name(), 0L);
        jdbc.query("SELECT status,COUNT(*) AS total FROM transactions GROUP BY status", rs -> {
            counts.put(rs.getString("status"), rs.getLong("total"));
        });
        return counts;
    }
    public List<Map<String, Object>> blockedStreams() {
        return jdbc.queryForList("SELECT a.account_id,a.currency,a.next_sequence AS expected_sequence,MIN(t.sequence_number) AS first_waiting_sequence FROM accounts a JOIN transactions t ON a.account_id=t.account_id AND a.currency=t.currency WHERE t.status <> 'PROCESSED' GROUP BY a.account_id,a.currency,a.next_sequence");
    }
}
