package com.finvanta.cbs.modules.account.validator;

import com.finvanta.cbs.modules.account.dto.request.OpenAccountRequest;
import com.finvanta.cbs.modules.account.dto.request.TransferRequest;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.util.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * CBS Account Module Business Validator per CBS ACCTVAL standard.
 *
 * <p>Centralizes complex business validations that go beyond Jakarta Bean Validation.
 * Per Tier-1 CBS standards: validators are separate from services to enable
 * reuse across multiple service methods and to keep services focused on orchestration.
 *
 * <p>Validation categories:
 * <ul>
 *   <li>Account lifecycle state validation (active, frozen, closed, dormant)</li>
 *   <li>Customer eligibility (KYC verified, active, not deactivated)</li>
 *   <li>Financial validations (balance sufficiency, OD limits)</li>
 *   <li>Regulatory validations (RBI minimum balance, dormancy rules)</li>
 * </ul>
 */
@Component
public class AccountValidator {

    /**
     * CBS Freeze Type Whitelist per RBI Freeze Guidelines / PMLA 2002.
     *
     * <p>These are the ONLY freeze types recognized by
     * {@link DepositAccount#isDebitAllowed()} / {@link DepositAccount#isCreditAllowed()}.
     * Any other value silently degrades to a total freeze (since neither helper
     * matches it), blocking legitimate operations and creating audit ambiguity.
     *
     * <p>Kept in sync with the freeze-type checks in
     * {@code DepositAccount.java} (isDebitAllowed / isCreditAllowed).
     */
    static final Set<String> ALLOWED_FREEZE_TYPES = Set.of(
            "DEBIT_FREEZE", "CREDIT_FREEZE", "TOTAL_FREEZE");

    /**
     * Validates that the supplied freeze type is one of the recognized CBS
     * freeze categories. Per CBS ACCTFRZ: unrecognized freeze types must be
     * rejected at the API boundary so the operator gets an explicit error
     * rather than the entity's freeze-helper falling through to "deny all".
     *
     * @throws BusinessException CBS-ACCT-012 if the type is null/blank or
     *                           not in {@link #ALLOWED_FREEZE_TYPES}
     */
    public void validateFreezeType(String freezeType) {
        if (freezeType == null || freezeType.isBlank()) {
            throw new BusinessException("CBS-ACCT-012",
                    "Freeze type is required. Allowed values: " + ALLOWED_FREEZE_TYPES);
        }
        if (!ALLOWED_FREEZE_TYPES.contains(freezeType)) {
            throw new BusinessException("CBS-ACCT-012",
                    "Invalid freeze type: '" + freezeType
                            + "'. Allowed values: " + ALLOWED_FREEZE_TYPES);
        }
    }

    /**
     * Validates prerequisites for account opening.
     * Per CBS ACCTOPN: customer must be active, KYC verified, and branch accessible.
     */
    public void validateAccountOpening(OpenAccountRequest request, Customer customer, LocalDate businessDate) {
        if (customer == null) {
            throw new BusinessException("CBS-CUST-001", "Customer not found");
        }
        if (!customer.isActive()) {
            throw new BusinessException("CBS-CUST-008",
                    "Cannot open account for deactivated customer: " + customer.getCustomerNumber());
        }
        if (!customer.isKycVerified()) {
            throw new BusinessException("CBS-CUST-005",
                    "KYC verification required before account opening per RBI KYC Direction");
        }
        if (customer.isKycExpired(businessDate)) {
            throw new BusinessException("CBS-CUST-004",
                    "KYC has expired. Re-KYC required per RBI Master Direction on KYC SS38");
        }
    }

    /**
     * Validates that an account is in a normal operational state (ACTIVE, not frozen,
     * not closed). Used for lifecycle operations (freeze, unfreeze, close) where
     * partial-freeze semantics do not apply.
     *
     * <p>For financial debit/credit operations, use {@link #validateAccountForDebit}
     * or {@link #validateAccountForCredit} instead -- those honor RBI/PMLA partial
     * freeze semantics (DEBIT_FREEZE / CREDIT_FREEZE / TOTAL_FREEZE).
     */
    public void validateAccountForTransaction(DepositAccount account) {
        if (account == null) {
            throw new BusinessException("CBS-ACCT-001", "Account not found");
        }
        DepositAccountStatus status = account.getAccountStatus();
        if (status == DepositAccountStatus.FROZEN) {
            throw new BusinessException("CBS-ACCT-003",
                    "Account is frozen: " + account.getAccountNumber()
                            + ". Freeze type: " + account.getFreezeType());
        }
        if (status == DepositAccountStatus.CLOSED) {
            throw new BusinessException("CBS-ACCT-004",
                    "Account is closed: " + account.getAccountNumber());
        }
        if (status != DepositAccountStatus.ACTIVE) {
            throw new BusinessException("CBS-ACCT-002",
                    "Account is not active: " + account.getAccountNumber()
                            + ". Current status: " + status);
        }
    }

    /**
     * Validates that an account can accept a DEBIT (withdrawal, transfer-out, charge).
     *
     * <p>Per RBI Freeze Guidelines / PMLA 2002 and CBS TRAN_VALIDATION:
     * <ul>
     *   <li>DEBIT_FREEZE   -- debits blocked, credits allowed</li>
     *   <li>CREDIT_FREEZE  -- debits allowed, credits blocked</li>
     *   <li>TOTAL_FREEZE   -- both blocked</li>
     *   <li>DORMANT        -- debits blocked (account must be reactivated)</li>
     *   <li>CLOSED         -- nothing allowed</li>
     * </ul>
     *
     * <p>Delegates to {@link DepositAccount#isDebitAllowed()} which is the single
     * source of truth for freeze-type semantics.
     */
    public void validateAccountForDebit(DepositAccount account) {
        if (account == null) {
            throw new BusinessException("CBS-ACCT-001", "Account not found");
        }
        if (account.isClosed()) {
            throw new BusinessException("CBS-ACCT-004",
                    "Account is closed: " + account.getAccountNumber());
        }
        if (!account.isDebitAllowed()) {
            throw new BusinessException("CBS-ACCT-003",
                    "Debit not allowed on account " + account.getAccountNumber()
                            + ". Status: " + account.getAccountStatus()
                            + (account.getFreezeType() != null
                                    ? ", freeze: " + account.getFreezeType() : ""));
        }
    }

    /**
     * Validates that an account can accept a CREDIT (deposit, transfer-in, interest credit).
     *
     * <p>Per RBI Freeze Guidelines / PMLA 2002: CREDIT_FREEZE and TOTAL_FREEZE block
     * credits; DEBIT_FREEZE allows them. DORMANT accounts accept credits (which may
     * trigger reactivation per RBI dormancy rules).
     *
     * <p>Delegates to {@link DepositAccount#isCreditAllowed()} which is the single
     * source of truth for freeze-type semantics.
     */
    public void validateAccountForCredit(DepositAccount account) {
        if (account == null) {
            throw new BusinessException("CBS-ACCT-001", "Account not found");
        }
        if (account.isClosed()) {
            throw new BusinessException("CBS-ACCT-004",
                    "Account is closed: " + account.getAccountNumber());
        }
        if (!account.isCreditAllowed()) {
            throw new BusinessException("CBS-ACCT-003",
                    "Credit not allowed on account " + account.getAccountNumber()
                            + ". Status: " + account.getAccountStatus()
                            + (account.getFreezeType() != null
                                    ? ", freeze: " + account.getFreezeType() : ""));
        }
    }

    /**
     * Validates sufficient balance for a debit (withdrawal/transfer).
     * Per CBS BAL_CHK: considers effective available balance.
     */
    public void validateSufficientBalance(DepositAccount account, BigDecimal amount) {
        BigDecimal effectiveAvailable = account.getEffectiveAvailable();
        if (effectiveAvailable.compareTo(amount) < 0) {
            throw new BusinessException("CBS-ACCT-006",
                    "Insufficient balance. Available: " + effectiveAvailable
                            + ", Requested: " + amount);
        }
    }

    /**
     * Validates that a debit will not breach the account's required minimum
     * balance per CBS ACCTLIMIT / RBI CASA norms.
     *
     * <p>Mirrors the legacy guard at
     * {@code src/main/java/com/finvanta/service/impl/DepositAccountServiceImpl.java:868-876}.
     * The check is independent of {@link #validateSufficientBalance}: a customer
     * may have funds available (per {@code effectiveAvailable}) yet still be
     * unable to withdraw because doing so would drop the ledger below the
     * product's minimum balance requirement.
     *
     * <p>Skipped when {@code minimumBalance <= 0} so PMJDY zero-balance accounts
     * and Current accounts (which have no MAB) pass through unchanged.
     *
     * <p>Per Tier-1 CBS Phase 2 enhancement: penalty-based MAB breach via
     * ChargeEngine is the alternative to outright rejection. This validator
     * implements the strict-rejection mode used by interactive CASA channels.
     *
     * @throws BusinessException CBS-ACCT-007 when the post-debit balance would
     *                           fall below the required minimum
     */
    public void validateMinimumBalance(DepositAccount account, BigDecimal amount) {
        if (account == null) {
            throw new BusinessException("CBS-ACCT-001", "Account not found");
        }
        BigDecimal minBalance = account.getMinimumBalance();
        if (minBalance == null || minBalance.signum() <= 0) {
            return;
        }
        BigDecimal postBalance = account.getLedgerBalance().subtract(amount);
        if (postBalance.compareTo(minBalance) < 0) {
            throw new BusinessException("CBS-ACCT-007",
                    "Debit of INR " + amount + " would breach minimum balance of INR "
                            + minBalance + " on account " + account.getAccountNumber()
                            + ". Post-debit balance would be INR " + postBalance);
        }
    }

    /**
     * Validates transfer-specific rules.
     * Per CBS ACCTXFER: same-account transfers are rejected.
     */
    public void validateTransfer(TransferRequest request) {
        if (request.fromAccount().equalsIgnoreCase(request.toAccount())) {
            throw new BusinessException("CBS-ACCT-011",
                    "Source and destination accounts cannot be the same");
        }
    }
}
