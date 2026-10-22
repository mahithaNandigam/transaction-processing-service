package com.mahitha.tps.service;

import com.mahitha.tps.config.ProcessingProperties;
import com.mahitha.tps.model.TransactionRecord;
import org.springframework.stereotype.Component;

@Component
public class FailureSimulator {
    private final ProcessingProperties properties;
    public FailureSimulator(ProcessingProperties properties) { this.properties = properties; }
    public void afterBalanceUpdate(TransactionRecord transaction) {
        if (transaction.attempts() < properties.getSimulatedFailures().getOrDefault(transaction.transactionId(), 0)) {
            throw new SimulatedTransientException();
        }
    }
    public static class SimulatedTransientException extends RuntimeException {}
}
