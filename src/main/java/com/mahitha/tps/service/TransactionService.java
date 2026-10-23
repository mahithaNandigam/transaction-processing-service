package com.mahitha.tps.service;

import com.mahitha.tps.config.ProcessingProperties;
import com.mahitha.tps.dto.*;
import com.mahitha.tps.exception.ApiException;
import com.mahitha.tps.model.*;
import com.mahitha.tps.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import org.slf4j.*;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TransactionService {
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private final TransactionRepository repository;
    private final TransactionTemplate transaction;
    private final Validator validator;
    private final ProcessingProperties properties;
    private final FailureSimulator simulator;
    private final MeterRegistry metrics;
    private final Clock clock;

    public TransactionService(TransactionRepository repository, PlatformTransactionManager manager,
                              Validator validator, ProcessingProperties properties, FailureSimulator simulator, MeterRegistry metrics, Clock clock) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.validator = validator;
        this.properties = properties;
        this.simulator = simulator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public SubmissionResult submit(TransactionRequest request) {
        validate(request);
        SubmissionResult result = transaction.execute(tx -> {
            repository.lock();
            var owner = repository.requestOwner(request.requestId());
            if (owner.isPresent() && !owner.get().equals(request.transactionId()))
                throw conflict("Request ID already belongs to another transaction");
            var existing = repository.find(request.transactionId());
            if (existing.isPresent()) {
                TransactionRecord old = existing.get();
                if (!samePayload(old, request))
                    throw conflict("Transaction ID already exists with different business fields");
                if (owner.isEmpty()) repository.addRequest(request.requestId(), request.transactionId(), "DUPLICATE");
                else repository.replayRequest(request.requestId());
                return new SubmissionResult(request.requestId(), "DUPLICATE", old, null);
            }
            var accounts = repository.accounts(request.accountId(), request.currency());
            if (accounts.isEmpty())
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Create the account and currency before submitting transactions");
            if (request.sequenceNumber() < accounts.getFirst().nextSequence())
                throw conflict("Sequence is older than the account's next required sequence");
            if (repository.sequenceOccupied(request))
                throw conflict("Sequence already belongs to another transaction in this account and currency");
            repository.insert(request, clock.instant());
            return new SubmissionResult(request.requestId(), "ACCEPTED", repository.find(request.transactionId()).orElseThrow(), null);
        });
        metrics.counter("transactions.submissions", "outcome", result.outcome()).increment();
        log.info("event=submission transactionId={} outcome={}", request.transactionId(), result.outcome());
        return result;
    }

    private void validate(TransactionRequest request) {
        if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Transaction must not be null");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            metrics.counter("transactions.submissions", "outcome", "INVALID").increment();
            throw new ApiException(HttpStatus.BAD_REQUEST, violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().collect(Collectors.joining("; ")));
        }
        try {
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported currency code");
        }
    }

    public List<SubmissionResult> submitBatch(List<TransactionRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Batch must contain 1 to 100 records");
        return requests.stream().map(r -> {
            try {
                return submit(r);
            } catch (ApiException e) {
                return new SubmissionResult(r == null ? null : r.requestId(), e.status() == HttpStatus.CONFLICT ? "CONFLICT" : "INVALID", null, e.getMessage());
            }
        }).toList();
    }

    public TransactionRecord get(String id) {
        return repository.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    public TransactionRecord retry(String id) {
        return transaction.execute(tx -> {
            repository.lock();
            TransactionRecord r = get(id);
            if (r.status() != TransactionStatus.FAILED) throw conflict("Only FAILED transactions can be recovered");
            repository.state(id, TransactionStatus.RETRY_PENDING, 0, null, clock.instant(), clock.instant());
            repository.attempt(id, 0, "MANUAL_RECOVERY", null, clock.instant());
            log.info("event=manual_recovery transactionId={}", id);
            return get(id);
        });
    }

    public TransactionRecord cancel(String id, CancelTransactionRequest request) {
        if (request == null || !validator.validate(request).isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reasonCode of 3-64 uppercase letters, digits or underscores is required");
        return transaction.execute(tx -> {
            repository.lock();
            TransactionRecord record = get(id);
            if (record.status() == TransactionStatus.CANCELLED) return record;
            if (record.status() != TransactionStatus.FAILED)
                throw conflict("Only FAILED transactions can be cancelled");
            if (record.sequenceNumber() != repository.nextSequence(record))
                throw conflict("Only the transaction blocking the next sequence can be cancelled");
            Instant now = clock.instant();
            repository.advanceSequence(record);
            repository.state(id, TransactionStatus.CANCELLED, record.attempts(), request.reasonCode(), null, now);
            repository.attempt(id, record.attempts(), "CANCELLED", request.reasonCode(), now);
            log.info("event=cancelled transactionId={}", id);
            return get(id);
        });
    }

    public void processBatch() {
        for (TransactionRecord candidate : repository.candidates(clock.instant(), properties.getBatchSize())) {
            long start = System.nanoTime();
            AtomicReference<TransactionRecord> observed = new AtomicReference<>();
            try {
                String outcome = transaction.execute(tx -> process(candidate.transactionId(), observed));
                if (outcome != null) metrics.counter("transactions.processing", "outcome", outcome).increment();
            } catch (RuntimeException e) {
                boolean retryable = e instanceof FailureSimulator.SimulatedTransientException
                        || e instanceof TransientDataAccessException || e instanceof DataAccessResourceFailureException;
                try {
                    // A failure before reading the record cannot safely be attributed to a business transaction.
                    if (observed.get() != null) recordFailure(observed.get(), retryable);
                    else
                        log.error("event=record_unavailable transactionId={} exceptionType={}", candidate.transactionId(), e.getClass().getSimpleName());
                } catch (RuntimeException bookkeepingError) {
                    // The failed business transaction already rolled back. Leave it eligible for a later poll.
                    log.error("event=failure_recording_unavailable transactionId={} exceptionType={}", candidate.transactionId(), bookkeepingError.getClass().getSimpleName());
                    metrics.counter("transactions.worker.errors", "stage", "failure_recording").increment();
                }
            } finally {
                metrics.timer("transactions.processing.duration").record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
    }

    private String process(String id, AtomicReference<TransactionRecord> observed) {
        repository.lock();
        TransactionRecord r = get(id);
        if (terminal(r.status())) return null;
        Instant now = clock.instant();
        if (r.nextAttemptAt() != null && r.nextAttemptAt().isAfter(now)) return null;
        if (r.sequenceNumber() != repository.nextSequence(r)) {
            if (r.status() != TransactionStatus.PENDING)
                repository.state(id, TransactionStatus.PENDING, r.attempts(), "WAITING_FOR_SEQUENCE", null, now);
            return null;
        }
        observed.set(r);
        repository.state(id, TransactionStatus.PROCESSING, r.attempts() + 1, null, null, now);
        BigDecimal delta = r.transactionType() == TransactionType.CREDIT ? r.amount() : r.amount().negate();
        BigDecimal balance = repository.balance(r).add(delta);
        if (balance.signum() < 0) {
            repository.state(id, TransactionStatus.FAILED, r.attempts() + 1, "INSUFFICIENT_FUNDS", null, now);
            repository.attempt(id, r.attempts() + 1, "FAILED", "INSUFFICIENT_FUNDS", now);
            log.warn("event=failed transactionId={} code=INSUFFICIENT_FUNDS", id);
            return "FAILED";
        }
        repository.apply(r, delta, balance, now);
        // Throw after both writes to demonstrate that the ledger and balance roll back together.
        simulator.afterBalanceUpdate(r);
        repository.state(id, TransactionStatus.PROCESSED, r.attempts() + 1, null, null, now);
        repository.attempt(id, r.attempts() + 1, "PROCESSED", null, now);
        log.info("event=processed transactionId={} attempt={}", id, r.attempts() + 1);
        return "PROCESSED";
    }

    private boolean terminal(TransactionStatus status) {
        return status == TransactionStatus.PROCESSED || status == TransactionStatus.FAILED || status == TransactionStatus.CANCELLED;
    }

    private void recordFailure(TransactionRecord observed, boolean retryable) {
        String id = observed.transactionId();
        String outcome = transaction.execute(tx -> {
            repository.lock();
            TransactionRecord r = get(id);
            // Another worker may have advanced this record after the failed transaction rolled back.
            if (terminal(r.status()) || r.version() != observed.version()) return null;
            int attempts = r.attempts() + 1;
            TransactionStatus status = !retryable || attempts >= properties.getMaxAttempts() ? TransactionStatus.FAILED : TransactionStatus.RETRY_PENDING;
            long delay = Math.min(properties.getRetryDelayMs(), 60000L) * (1L << Math.min(attempts - 1, 10));
            Instant next = status == TransactionStatus.FAILED ? null : clock.instant().plusMillis(Math.min(delay, 60000L));
            String code = retryable ? "TRANSIENT_PROCESSING_FAILURE" : "NON_RETRYABLE_PROCESSING_FAILURE";
            repository.state(id, status, attempts, code, next, clock.instant());
            repository.attempt(id, attempts, status.name(), code, clock.instant());
            log.warn("event=processing_failure transactionId={} status={} attempt={}", id, status, attempts);
            return status.name();
        });
        if (outcome != null) metrics.counter("transactions.processing", "outcome", outcome).increment();
    }

    public Map<String, Object> summary() {
        return transaction.execute(tx -> {
            repository.lock();
            return Map.of("countsByStatus", repository.counts(), "blockedStreams", repository.blockedStreams(), "requestCounts", repository.requestCounts());
        });
    }

    public Map<String, Object> request(String id) {
        return transaction.execute(tx -> {
            repository.lock();
            var result = repository.request(id);
            if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "Request not found");
            result.put("transaction", get((String) result.get("transactionId")));
            return result;
        });
    }

    public List<Map<String, Object>> attempts(String id, int limit, long offset) {
        get(id);
        return repository.attempts(id, limit, offset);
    }

    public Map<String, Object> account(String id, String currency) {
        var account = repository.account(id, currency);
        if (account == null) throw new ApiException(HttpStatus.NOT_FOUND, "Account and currency not found");
        return account;
    }

    public List<Map<String, Object>> ledger(String id, String currency, int limit, long offset) {
        account(id, currency);
        return repository.ledger(id, currency, limit, offset);
    }

    private boolean samePayload(TransactionRecord old, TransactionRequest r) {
        return old.accountId().equals(r.accountId()) && old.transactionType() == r.transactionType()
                && old.amount().compareTo(r.amount()) == 0 && old.currency().equals(r.currency()) && old.sequenceNumber() == r.sequenceNumber();
    }

    private ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
