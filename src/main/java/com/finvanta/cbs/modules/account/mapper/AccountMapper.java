package com.finvanta.cbs.modules.account.mapper;

import com.finvanta.cbs.modules.account.dto.response.AccountResponse;
import com.finvanta.cbs.modules.account.dto.response.BalanceResponse;
import com.finvanta.cbs.modules.account.dto.response.TxnResponse;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.util.PiiMaskingUtil;

import org.springframework.stereotype.Component;

/**
 * CBS Account Module Mapper per Tier-1 DTO isolation standards.
 *
 * <p>Centralizes all entity-to-DTO conversions for the Account bounded context.
 * Per CBS Tier-1 architecture: mappers enforce PII masking at the boundary
 * so no entity with unmasked PII can leak to the API layer.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Manual mapping (not MapStruct) for full control over PII masking logic</li>
 *   <li>Null-safe -- handles null entities and null nested objects gracefully</li>
 *   <li>Stateless -- all methods are pure functions, thread-safe</li>
 * </ul>
 *
 * <p>Per RBI IT Governance SS8.5: PAN masked to last 4, Aadhaar masked to last 4.
 * The mapper is the SINGLE POINT where masking is enforced for API responses.
 */
@Component
public class AccountMapper {

    /**
     * Maps {@code DepositAccount} entity to {@code AccountResponse} DTO.
     * PII (PAN) masked. Branch and Customer names resolved from eager-loaded relations.
     */
    public AccountResponse toAccountResponse(DepositAccount entity) {
        if (entity == null) {
            return null;
        }
        String customerName = null;
        String customerNumber = null;
        String maskedPan = null;
        if (entity.getCustomer() != null) {
            customerName = entity.getCustomer().getFirstName()
                    + " " + entity.getCustomer().getLastName();
            customerNumber = entity.getCustomer().getCustomerNumber();
            maskedPan = PiiMaskingUtil.maskPan(entity.getCustomer().getPanNumber());
        }
        String branchCode = null;
        String branchName = null;
        if (entity.getBranch() != null) {
            branchCode = entity.getBranch().getBranchCode();
            branchName = entity.getBranch().getBranchName();
        }

        return new AccountResponse(
                entity.getId(),
                entity.getAccountNumber(),
                customerName,
                customerNumber,
                maskedPan,
                entity.getAccountType() != null ? entity.getAccountType().name() : null,
                entity.getAccountStatus() != null ? entity.getAccountStatus().name() : null,
                entity.getProductCode(),
                branchCode,
                branchName,
                entity.getCurrencyCode(),
                entity.getAvailableBalance(),
                entity.getLedgerBalance(),
                entity.getHoldAmount(),
                entity.getUnclearedAmount(),
                entity.getOdLimit(),
                entity.getEffectiveAvailable(),
                entity.getInterestRate(),
                entity.getOpenedDate(),
                entity.getLastTransactionDate(),
                entity.getNomineeName(),
                entity.getFreezeType(),
                entity.getFreezeReason()
        );
    }

    /**
     * Maps {@code DepositAccount} to minimal {@code BalanceResponse} for UPI/IMPS.
     */
    public BalanceResponse toBalanceResponse(DepositAccount entity) {
        if (entity == null) {
            return null;
        }
        return new BalanceResponse(
                entity.getAccountNumber(),
                entity.getAccountStatus() != null ? entity.getAccountStatus().name() : null,
                entity.getLedgerBalance(),
                entity.getAvailableBalance(),
                entity.getHoldAmount(),
                entity.getUnclearedAmount(),
                entity.getOdLimit(),
                entity.getEffectiveAvailable()
        );
    }

    /**
     * Maps {@code DepositTransaction} entity to {@code TxnResponse} DTO.
     */
    public TxnResponse toTxnResponse(DepositTransaction entity) {
        if (entity == null) {
            return null;
        }
        String accountNumber = entity.getDepositAccount() != null
                ? entity.getDepositAccount().getAccountNumber() : null;
        return new TxnResponse(
                entity.getId(),
                entity.getTransactionRef(),
                entity.getVoucherNumber(),
                accountNumber,
                entity.getTransactionType(),
                entity.getDebitCredit(),
                entity.getAmount(),
                entity.getBalanceBefore(),
                entity.getBalanceAfter(),
                entity.getNarration(),
                entity.getChannel(),
                entity.getValueDate(),
                entity.getPostingDate(),
                entity.getJournalEntryId(),
                entity.isReversed(),
                entity.getReversedByRef()
        );
    }
}
