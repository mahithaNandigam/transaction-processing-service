package com.mahitha.tps.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String accountId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal openingBalance,
        @Min(1) @Max(9223372036854775806L) Long startingSequence) {
    public long initialSequence() {
        return startingSequence == null ? 1 : startingSequence;
    }
}
