package com.finvanta.cbs.modules.teller.dto.response;

import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CBS Teller Cash Withdrawal Response per CBS TELLER_CASH_WD standard.
 *
 * <p>Returned to the BFF / JSP after a cash withdrawal posts (or routes to
 * maker-checker). Layout deliberately mirrors {@link CashDepositResponse}
 * so the BFF can render the deposit-slip and withdrawal-slip components
 * with the same template and only swap labels and the direction flag.
 *
 * <p>Differences from the deposit response:
 * <ul>
 *   <li>{@code chequeNumber} surfaced for paid-cheque records (cheque-book
 *       reconciliation in the clearing module reads this field).</li>
 *   <li>{@code ficnTriggered} is always false (withdrawals cannot pay out
 *       counterfeit; the field is retained for response-shape symmetry so
 *       a unified BFF receipt component can render either kind).</li>
 *   <li>{@code denominations} echoes the {@code direction='OUT'} rows; each
 *       {@code DenominationLine} uses the same record as the deposit
 *       response for symmetry but with no {@code counterfeitCount}.</li>
 * </ul>
 */
public record CashWithdrawalResponse(

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

        /** Till after the withdrawal is applied (or unchanged if PENDING_APPROVAL). */
        BigDecimal tillBalanceAfter,
        Long tillId,
        String tellerUserId,

        /** Per-denomination breakdown of the cash paid out to the customer. */
        List<DenominationLine> denominations,

        /** Set when the deposit triggered a CTR (cash transaction report) per PMLA. */
        boolean ctrTriggered,

        /**
         * Cheque number for paid-cheque withdrawals. Null for plain wallet
         * withdrawals. Surfaced on the receipt + statement.
         */
        String chequeNumber
) {
    /**
     * Per-denomination line on the response. Same record shape as the
     * deposit response so the BFF receipt template is direction-agnostic.
     * For withdrawals {@code counterfeitCount} is always 0; it is retained
     * on the record for shape symmetry with deposits.
     */
    public record DenominationLine(
            IndianCurrencyDenomination denomination,
            long unitCount,
            BigDecimal totalValue,
            long counterfeitCount
    ) {
    }
}
