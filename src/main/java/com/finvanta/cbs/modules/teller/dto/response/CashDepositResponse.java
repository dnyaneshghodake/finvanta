package com.finvanta.cbs.modules.teller.dto.response;

import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CBS Teller Cash Deposit Response per CBS TELLER_CASH_DEP standard.
 *
 * <p>Returned to the BFF / JSP after a cash deposit is posted (or routed to
 * maker-checker). Contains the customer-facing receipt fields PLUS the
 * teller-side post-state of the till.
 *
 * <p>{@code pendingApproval = true} means the GL has NOT been posted and the
 * customer balance is unchanged. The transaction is in the workflow queue
 * awaiting a checker; the BFF should display a "PENDING APPROVAL" banner
 * instead of a "Deposit successful" toast. This mirrors the
 * {@code TransactionResult.isPendingApproval()} contract.
 *
 * <p>{@code denominations} echoes the breakdown the teller submitted, useful
 * for the printed deposit slip and for client-side reconciliation that the
 * server stored what the operator entered. Counterfeit-flagged rows are
 * present with their {@code counterfeitCount} populated; in that case
 * {@code pendingApproval} will be true and the deposit is in FICN review.
 */
public record CashDepositResponse(

        String transactionRef,
        String voucherNumber,
        String accountNumber,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        LocalDate valueDate,
        LocalDateTime postingDate,
        String narration,
        String channel,
        boolean pendingApproval,

        /** Till after the deposit is applied (or unchanged if PENDING_APPROVAL). */
        BigDecimal tillBalanceAfter,
        Long tillId,
        String tellerUserId,

        /** Per-denomination breakdown echoed from the request for the printed slip. */
        List<DenominationLine> denominations,

        /** Set when the deposit triggered a CTR (cash transaction report) per PMLA. */
        boolean ctrTriggered,

        /** Set when one or more denomination rows were flagged counterfeit (FICN). */
        boolean ficnTriggered
) {
    /**
     * Per-denomination line on the response, mirroring the request entries but
     * including the computed {@code totalValue} so the deposit slip can render
     * INR amounts directly without re-doing the math client-side.
     */
    public record DenominationLine(
            IndianCurrencyDenomination denomination,
            long unitCount,
            BigDecimal totalValue,
            long counterfeitCount
    ) {
    }
}
