package com.mahitha.tps.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "processing")
public class ProcessingProperties {
    @Min(1)
    private int maxAttempts = 3;
    @Min(1)
    private long retryDelayMs = 1000;
    @Min(1)
    private int batchSize = 100;
    private Map<String, @Min(0) Integer> simulatedFailures = new HashMap<>();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int value) {
        maxAttempts = value;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long value) {
        retryDelayMs = value;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int value) {
        batchSize = value;
    }

    public Map<String, Integer> getSimulatedFailures() {
        return simulatedFailures;
    }

    public void setSimulatedFailures(Map<String, Integer> value) {
        simulatedFailures = value;
    }
}
