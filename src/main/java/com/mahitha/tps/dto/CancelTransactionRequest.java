package com.mahitha.tps.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * An operator-supplied reason code; free-form customer data is not accepted.
 */
public record CancelTransactionRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String reasonCode) {
}
