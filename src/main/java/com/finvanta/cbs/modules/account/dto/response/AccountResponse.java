package com.finvanta.cbs.modules.account.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS CASA Account Response DTO per Finacle ACCTINQ / Temenos ACCOUNT.DETAILS.
 *
 * <p>Projects only the fields required by the API contract. Entity-internal
 * fields (version, hibernate proxies, lazy collections) are never exposed.
 * PII fields (customer PAN/Aadhaar) are masked by {@code AccountMapper}.
 *
 * <p>Immutable record -- thread-safe, no defensive copies needed.
 */
public record AccountResponse(
    Long id,
    String accountNumber,
    String customerName,
    String customerNumber,
    String maskedPan,
    String accountType,
    String accountStatus,
    String productCode,
    String branchCode,
    String branchName,
    String currencyCode,
    BigDecimal availableBalance,
    BigDecimal ledgerBalance,
    BigDecimal holdAmount,
    BigDecimal unclearedAmount,
    BigDecimal odLimit,
    BigDecimal effectiveAvailable,
    BigDecimal interestRate,
    LocalDate openedDate,
    LocalDate lastTransactionDate,
    String nomineeName,
    String freezeType,
    String freezeReason
) {
}
