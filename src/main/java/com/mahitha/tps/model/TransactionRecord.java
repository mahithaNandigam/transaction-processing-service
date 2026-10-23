package com.mahitha.tps.model;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRecord(String transactionId, String accountId, TransactionType transactionType,
                                BigDecimal amount, String currency, long sequenceNumber, TransactionStatus status,
                                int attempts, String errorCode, Instant nextAttemptAt, Instant createdAt,
                                Instant updatedAt, long version) {
}
