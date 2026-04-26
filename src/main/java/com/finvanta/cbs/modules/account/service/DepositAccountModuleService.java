package com.finvanta.cbs.modules.account.service;

import com.finvanta.cbs.modules.account.dto.request.FinancialRequest;
import com.finvanta.cbs.modules.account.dto.request.OpenAccountRequest;
import com.finvanta.cbs.modules.account.dto.request.TransferRequest;
import com.finvanta.cbs.modules.account.validator.AccountValidator;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;

import java.util.List;

/**
 * CBS CASA Module Service Interface per CBS CUSTACCT standard.
 *
 * <p>This is the refactored service interface for the Account bounded context.
 * Per Tier-1 CBS standards:
 * <ul>
 *   <li>All methods carry {@code @Transactional} in the implementation</li>
 *   <li>Business validations delegated to {@link AccountValidator}</li>
 *   <li>Financial postings route through TransactionEngine.execute()</li>
 *   <li>Audit trail via AuditService on every state mutation</li>
 *   <li>Returns domain entities -- the controller mapper handles DTO conversion</li>
 * </ul>
 */
public interface DepositAccountModuleService {

    // === Account Lifecycle ===

    DepositAccount openAccount(OpenAccountRequest request);

    DepositAccount activateAccount(String accountNumber);

    DepositAccount freezeAccount(String accountNumber, String freezeType, String reason);

    DepositAccount unfreezeAccount(String accountNumber);

    DepositAccount closeAccount(String accountNumber, String reason);

    List<DepositAccount> getPendingAccounts();

    // === Financial Operations ===

    DepositTransaction deposit(String accountNumber, FinancialRequest request);

    DepositTransaction withdraw(String accountNumber, FinancialRequest request);

    DepositTransaction transfer(TransferRequest request);

    DepositTransaction reverseTransaction(String transactionRef, String reason);

    // === Inquiry ===

    DepositAccount getAccount(String accountNumber);

    List<DepositTransaction> getMiniStatement(String accountNumber, int count);
}
