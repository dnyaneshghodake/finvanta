package com.finvanta.cbs.modules.account.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Deposit Transaction Response DTO per Finacle TRAN_INQ / Temenos STMT.ENTRY.
 *
 * <p>Immutable projection of {@code DepositTransaction} for API consumers.
 * Contains only the fields needed for statement rendering and reconciliation.
 */
public record TxnResponse(
    Long id,
    String transactionRef,
    String voucherNumber,
    String accountNumber,
    String transactionType,
    String debitCredit,
    BigDecimal amount,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter,
    String narration,
    String channel,
    LocalDate valueDate,
    LocalDateTime postingDate,
    Long journalEntryId,
    boolean reversed,
    String reversedByRef
) {
}
