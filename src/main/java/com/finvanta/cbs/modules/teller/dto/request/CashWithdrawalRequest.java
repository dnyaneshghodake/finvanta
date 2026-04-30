package com.finvanta.cbs.modules.teller.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * CBS Teller Cash Withdrawal Request per CBS TELLER_CASH_WD standard.
 *
 * <p>Customer-presents-cheque or wallet-withdrawal at the counter. The
 * structure mirrors {@link CashDepositRequest} for consistency, but with
 * three direction-aware differences:
 *
 * <ul>
 *   <li><b>No counterfeit field on rows:</b> the bank only pays out genuine
 *       notes from its till. The Bean Validation rule
 *       {@link #counterfeitCountIsZeroOnWithdrawal()} rejects any non-zero
 *       counterfeit count at the boundary so a malformed call cannot reach
 *       the service layer. Coin counterfeits are already rejected by the
 *       {@link DenominationEntry} compact constructor.</li>
 *   <li><b>{@code beneficiaryName} replaces {@code depositorName}:</b> per
 *       PMLA 2002 §12, the recipient of cash above CTR threshold is the
 *       reportable identity (whereas for a deposit it is the tendering
 *       party). The validator uses this field for AML matching.</li>
 *   <li><b>{@code chequeNumber} optional:</b> when the withdrawal is
 *       presented via cheque, the cheque number is captured here for the
 *       deposit-transaction record. Plain wallet withdrawals leave it null.</li>
 * </ul>
 *
 * <p>{@code idempotencyKey} is mandatory (same rationale as deposit -- a
 * network retry on a withdrawal that double-debits is the worst possible
 * outcome at the counter).
 */
public record CashWithdrawalRequest(

        @NotBlank(message = "Source account number is required")
        @Size(max = 40, message = "Account number too long")
        String accountNumber,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        BigDecimal amount,

        @NotEmpty(message = "Denomination breakdown is required for teller cash withdrawal")
        @Valid
        List<DenominationEntry> denominations,

        @NotBlank(message = "Idempotency key is required for cash withdrawal")
        @Size(max = 100, message = "Idempotency key too long")
        String idempotencyKey,

        /**
         * Per PMLA 2002 §12 / RBI KYC §16: the person physically receiving
         * the cash. For self-withdrawal this is the account holder name; for
         * authorized-representative withdrawals the actual receiver. Stored
         * for AML / KYC trail and printed on the cash payout slip.
         */
        @NotBlank(message = "Beneficiary name is required per PMLA 2002")
        @Size(max = 200, message = "Beneficiary name too long")
        String beneficiaryName,

        @Size(max = 20, message = "Beneficiary mobile too long")
        String beneficiaryMobile,

        /**
         * Cheque number when withdrawal is via cheque. Optional for plain
         * wallet/passbook withdrawals. Stored on
         * {@code DepositTransaction.chequeNumber} for the statement and for
         * paid-cheque reconciliation in the clearing module.
         */
        @Size(max = 20, message = "Cheque number too long")
        String chequeNumber,

        @Size(max = 500, message = "Narration too long")
        String narration
) {

    /**
     * CBS withdrawal-FICN guard. The bank never pays out counterfeit notes
     * (genuine notes are kept in vault and dispensed at the counter), so any
     * non-zero {@code counterfeitCount} on a withdrawal row is a malformed
     * request and is rejected before the service layer sees it.
     *
     * <p>Annotated {@link AssertTrue} so Jakarta Validation surfaces the
     * field on the generic {@code MethodArgumentNotValidException} rather
     * than as a hand-thrown {@code BusinessException} from the service.
     */
    @AssertTrue(message = "Counterfeit notes cannot be paid out on a withdrawal")
    public boolean isCounterfeitCountZeroOnWithdrawal() {
        if (denominations == null) return true;
        for (DenominationEntry e : denominations) {
            if (e != null && e.counterfeitCount() > 0) {
                return false;
            }
        }
        return true;
    }
}
