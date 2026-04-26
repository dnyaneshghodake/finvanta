package com.finvanta.cbs.modules.account.dto.response;

import java.math.BigDecimal;

/**
 * CBS Real-Time Balance Inquiry Response per Finacle BAL_INQ / Temenos ACCOUNT.BALANCE.
 *
 * <p>Used by UPI/IMPS/NEFT for real-time balance checks.
 * Minimal projection -- no PII, no account metadata.
 */
public record BalanceResponse(
    String accountNumber,
    String accountStatus,
    BigDecimal ledgerBalance,
    BigDecimal availableBalance,
    BigDecimal holdAmount,
    BigDecimal unclearedAmount,
    BigDecimal odLimit,
    BigDecimal effectiveAvailable
) {
}
