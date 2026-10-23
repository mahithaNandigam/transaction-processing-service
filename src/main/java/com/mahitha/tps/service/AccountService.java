package com.mahitha.tps.service;

import com.mahitha.tps.dto.CreateAccountRequest;
import com.mahitha.tps.exception.ApiException;
import com.mahitha.tps.model.Account;
import com.mahitha.tps.repository.TransactionRepository;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;

@Service
public class AccountService {
    private final TransactionRepository repository;
    private final Validator validator;
    private final TransactionTemplate transaction;

    public AccountService(TransactionRepository repository, Validator validator, PlatformTransactionManager manager) {
        this.repository = repository;
        this.validator = validator;
        this.transaction = new TransactionTemplate(manager);
    }

    public Account create(CreateAccountRequest request) {
        if (request == null || !validator.validate(request).isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Account ID, currency, nonnegative opening balance and positive starting sequence are required");
        try {
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported currency code");
        }
        return transaction.execute(tx -> {
            repository.lock();
            if (repository.account(request.accountId(), request.currency()) != null)
                throw new ApiException(HttpStatus.CONFLICT, "Account and currency already exist");
            repository.createAccount(request);
            return get(request.accountId(), request.currency());
        });
    }

    public Account get(String id, String currency) {
        var accounts = repository.accounts(id, currency);
        if (accounts.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Account not found");
        if (accounts.size() > 1)
            throw new ApiException(HttpStatus.CONFLICT, "Specify the currency query parameter for this multi-currency account");
        return accounts.getFirst();
    }
}
