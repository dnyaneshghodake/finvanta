package com.finvanta.cbs.modules.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * CBS Fund Transfer Request DTO per CBS ACCTXFER standard.
 */
public record TransferRequest(

    @NotBlank(message = "Source account is required")
    String fromAccount,

    @NotBlank(message = "Destination account is required")
    String toAccount,

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    BigDecimal amount,

    String narration,

    String idempotencyKey
) {
}
