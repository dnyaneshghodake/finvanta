package com.finvanta.cbs.modules.account.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * CBS Financial Transaction Request DTO per Finacle TRAN_INPUT / Temenos FT.INPUT.
 *
 * <p>Used for deposit, withdrawal, and single-account financial operations.
 * Transfer operations use {@code TransferRequest} which carries two account references.
 */
public record FinancialRequest(

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    BigDecimal amount,

    String narration,

    String idempotencyKey,

    String channel
) {
}
