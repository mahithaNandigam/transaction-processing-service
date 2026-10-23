package com.mahitha.tps.controller;

import com.mahitha.tps.service.TransactionService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping({"/api/v1/accounts/{id}/{currency}", "/api/accounts/{id}/{currency}"})
public class AccountController {
    private final TransactionService service;

    public AccountController(TransactionService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String id, @PathVariable String currency) {
        return service.account(id, currency);
    }

    @GetMapping("/ledger")
    public List<Map<String, Object>> ledger(@PathVariable String id, @PathVariable String currency,
                                            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
                                            @RequestParam(defaultValue = "0") @Min(0) long offset) {
        return service.ledger(id, currency, limit, offset);
    }
}
