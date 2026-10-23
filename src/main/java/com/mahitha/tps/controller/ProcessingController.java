package com.mahitha.tps.controller;

import com.mahitha.tps.service.TransactionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ProcessingController {
    private final TransactionService service;

    public ProcessingController(TransactionService service) {
        this.service = service;
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable String id) {
        return service.request(id);
    }

    @GetMapping("/processing/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }
}
