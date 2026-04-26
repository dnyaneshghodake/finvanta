package com.finvanta.cbs.modules.account.validator;

import com.finvanta.cbs.modules.account.dto.request.OpenAccountRequest;
import com.finvanta.cbs.modules.account.dto.request.TransferRequest;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.util.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;

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
     * Validates that an account is in a state that permits financial transactions.
     * Per CBS TRAN_VALIDATION: only ACTIVE accounts accept debits/credits.
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
