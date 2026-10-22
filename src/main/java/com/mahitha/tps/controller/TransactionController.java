package com.mahitha.tps.controller;

import com.mahitha.tps.dto.*;
import com.mahitha.tps.model.TransactionRecord;
import com.mahitha.tps.service.TransactionService;
import com.mahitha.tps.exception.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping({"/api/v1/transactions", "/api/transactions"})
public class TransactionController {
    private final TransactionService service;
    private final ObjectMapper mapper;
    public TransactionController(TransactionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }
    @PostMapping
    public ResponseEntity<SubmissionResult> submit(@RequestBody TransactionRequest request) {
        var result = service.submit(request);
        return ResponseEntity.status(result.outcome().equals("DUPLICATE") ? 200 : 202)
                .location(URI.create("/api/v1/transactions/" + result.transaction().transactionId())).body(result);
    }
    @PostMapping("/batch")
    public List<SubmissionResult> batch(@RequestBody List<JsonNode> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Batch must contain 1 to 100 records");
        List<SubmissionResult> results = new ArrayList<>();
        for (JsonNode node : requests) {
            TransactionRequest request;
            try { request = mapper.treeToValue(node, TransactionRequest.class); }
            catch (RuntimeException exception) {
                results.add(new SubmissionResult(null, "INVALID", null, "Malformed transaction fields"));
                continue;
            }
            results.add(service.submitBatch(Collections.singletonList(request)).getFirst());
        }
        return results;
    }
    @GetMapping("/summary")
    public Map<String, Object> summary() { return service.summary(); }
    @GetMapping("/{id}")
    public TransactionRecord get(@PathVariable String id) { return service.get(id); }
    @GetMapping("/{id}/attempts")
    public List<Map<String, Object>> attempts(@PathVariable String id,
            @RequestParam(defaultValue = "100") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) int limit,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) long offset) {
        return service.attempts(id, limit, offset);
    }
    @PostMapping("/{id}/retry")
    public ResponseEntity<TransactionRecord> retry(@PathVariable String id) { return ResponseEntity.accepted().body(service.retry(id)); }
}
