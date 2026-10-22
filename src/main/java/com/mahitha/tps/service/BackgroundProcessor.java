package com.mahitha.tps.service;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "processing.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundProcessor {
    private static final Logger log = LoggerFactory.getLogger(BackgroundProcessor.class);
    private final TransactionService service;
    public BackgroundProcessor(TransactionService service) { this.service = service; }
    @Scheduled(fixedDelayString = "${processing.poll-delay-ms:500}")
    public void poll() {
        try { service.processBatch(); }
        catch (RuntimeException e) { log.error("event=worker_poll_failed exceptionType={}", e.getClass().getSimpleName()); }
    }
}
