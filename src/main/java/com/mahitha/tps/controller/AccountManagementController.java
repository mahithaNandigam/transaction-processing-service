package com.mahitha.tps.controller;

import com.mahitha.tps.dto.CreateAccountRequest;
import com.mahitha.tps.model.Account;
import com.mahitha.tps.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping({"/api/v1/accounts", "/api/accounts"})
public class AccountManagementController {
    private final AccountService service;
    public AccountManagementController(AccountService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<Account> create(@RequestBody CreateAccountRequest request) {
        Account account = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.accountId() + "?currency=" + account.currency())).body(account);
    }
    @GetMapping("/{id}")
    public Account get(@PathVariable String id, @RequestParam(required = false) String currency) { return service.get(id, currency); }
}
