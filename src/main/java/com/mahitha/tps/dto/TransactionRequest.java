package com.mahitha.tps.dto;

import com.mahitha.tps.model.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String transactionId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String requestId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String accountId,
        @NotNull TransactionType transactionType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Min(1) @Max(9223372036854775806L) Long sequenceNumber) {}
