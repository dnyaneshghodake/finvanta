package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.ClearingTransactionRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Clearing/Settlement Service per Finacle CLG_MASTER.
 *
 * @deprecated This service is being replaced by {@link ClearingEngine} which provides:
 * - Rail-specific suspense GLs (NEFT/RTGS/IMPS/UPI inward + outward)
 * - ClearingDirection (INWARD/OUTWARD) with separate GL flows
 * - ClearingStatus state machine with lifecycle tracking
 * - ClearingCycle for NEFT batch netting
 * - SettlementBatch for RBI settlement tracking
 * - Idempotent external reference (UTR) validation
 * - Branch-scoped clearing transactions
 * - Maker-checker for high-value outward payments
 *
 * This class is retained temporarily for backward compatibility with
 * EodOrchestrator.validateSuspenseBalance(). New clearing operations
 * must use ClearingEngine.
 */
@Deprecated(forRemoval = true)
@Service
public class ClearingService {

    private static final Logger log = LoggerFactory.getLogger(ClearingService.class);

    private final ClearingTransactionRepository clearingRepository;
    private final GLMasterRepository glRepository;
    private final TransactionEngine transactionEngine;

    public ClearingService(
            ClearingTransactionRepository clearingRepository,
            GLMasterRepository glRepository,
            TransactionEngine transactionEngine) {
        this.clearingRepository = clearingRepository;
        this.glRepository = glRepository;
        this.transactionEngine = transactionEngine;
    }

    /**
     * Initiate clearing transaction (post to suspense account).
     *
     * @param clearingRef Unique clearing reference
     * @param sourceType NEFT, RTGS, IMPS, CHEQUE, UPI
     * @param amount Clearing amount
     * @param customerAccountRef Customer account reference
     * @param counterpartyDetails Counterparty information
     * @param businessDate CBS business date
     * @return Created clearing transaction
     */
    @Transactional
    public ClearingTransaction initiateClearingTransaction(
            String clearingRef,
            String sourceType,
            BigDecimal amount,
            String customerAccountRef,
            String counterpartyDetails,
            LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Clearing amount must be positive");
        }

        // CBS: Create clearing transaction record (INITIATED)
        ClearingTransaction transaction = new ClearingTransaction();
        transaction.setTenantId(tenantId);
        transaction.setClearingRef(clearingRef);
        transaction.setSourceType(sourceType);
        transaction.setAmount(amount);
        transaction.setCustomerAccountRef(customerAccountRef);
        transaction.setCounterpartyDetails(counterpartyDetails);
        transaction.setStatus("INITIATED");
        transaction.setInitiatedDate(LocalDateTime.now());
        transaction.setBusinessDate(businessDate);

        ClearingTransaction savedTransaction = clearingRepository.save(transaction);

        // CBS: Post to suspense account via TransactionEngine
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest("1100", DebitCredit.DEBIT, amount, "Clearing initiated: " + sourceType),
                new JournalLineRequest("2400", DebitCredit.CREDIT, amount, "Clearing suspense: " + sourceType));

        TransactionResult result = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("CLEARING")
                .transactionType("CLEARING_" + sourceType)
                .accountReference(customerAccountRef)
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(null)
                .narration("Clearing initiated: " + clearingRef + " (" + sourceType + ")")
                .journalLines(lines)
                .systemGenerated(false)
                .initiatedBy("SYSTEM")
                .build());

        savedTransaction.setSuspenseJournalId(result.getJournalEntryId());
        savedTransaction.setStatus("PENDING");
        clearingRepository.save(savedTransaction);

        log.info(
                "Clearing transaction initiated: ref={}, type={}, amount={}, status=PENDING",
                clearingRef,
                sourceType,
                amount);

        return savedTransaction;
    }

    /**
     * Confirm clearing (move from suspense to settlement GL).
     *
     * @param clearingRef Clearing reference
     * @param settlementGlCode Settlement GL code
     * @param businessDate CBS business date
     */
    @Transactional
    public void confirmClearing(String clearingRef, String settlementGlCode, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        ClearingTransaction transaction = clearingRepository
                .findByTenantIdAndClearingRef(tenantId, clearingRef)
                .orElseThrow(() -> new BusinessException("CLEARING_NOT_FOUND", "Clearing not found: " + clearingRef));

        if (!"PENDING".equals(transaction.getStatus())) {
            throw new BusinessException(
                    "INVALID_CLEARING_STATUS",
                    "Clearing must be PENDING for confirmation. Current: " + transaction.getStatus());
        }

        // CBS: Post reversal of suspense and settlement
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(
                        "2400", DebitCredit.DEBIT, transaction.getAmount(), "Clearing confirmed: " + clearingRef),
                new JournalLineRequest(
                        settlementGlCode,
                        DebitCredit.CREDIT,
                        transaction.getAmount(),
                        "Clearing settlement: " + clearingRef));

        TransactionResult result = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("CLEARING")
                .transactionType("CLEARING_CONFIRM")
                .accountReference(transaction.getCustomerAccountRef())
                .amount(transaction.getAmount())
                .valueDate(businessDate)
                .branchCode(null)
                .narration("Clearing confirmed: " + clearingRef)
                .journalLines(lines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build());

        transaction.setSettlementJournalId(result.getJournalEntryId());
        transaction.setStatus("CONFIRMED");
        transaction.setSettlementDate(LocalDateTime.now());
        clearingRepository.save(transaction);

        log.info("Clearing transaction confirmed: ref={}", clearingRef);
    }

    /**
     * Fail clearing (reverse suspense entry).
     *
     * @param clearingRef Clearing reference
     * @param failureReason Reason for failure
     * @param businessDate CBS business date
     */
    @Transactional
    public void failClearing(String clearingRef, String failureReason, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        ClearingTransaction transaction = clearingRepository
                .findByTenantIdAndClearingRef(tenantId, clearingRef)
                .orElseThrow(() -> new BusinessException("CLEARING_NOT_FOUND", "Clearing not found: " + clearingRef));

        if ("SETTLED".equals(transaction.getStatus()) || "FAILED".equals(transaction.getStatus())) {
            throw new BusinessException(
                    "INVALID_CLEARING_STATUS", "Cannot fail clearing in status: " + transaction.getStatus());
        }

        // CBS: Post reversal (DR Suspense / CR Bank)
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(
                        "2400", DebitCredit.DEBIT, transaction.getAmount(), "Clearing failed/reversed: " + clearingRef),
                new JournalLineRequest(
                        "1100", DebitCredit.CREDIT, transaction.getAmount(), "Clearing reversal: " + clearingRef));

        TransactionResult result = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("CLEARING")
                .transactionType("CLEARING_FAIL")
                .accountReference(transaction.getCustomerAccountRef())
                .amount(transaction.getAmount())
                .valueDate(businessDate)
                .branchCode(null)
                .narration("Clearing failed: " + clearingRef + " - " + failureReason)
                .journalLines(lines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build());

        transaction.setSettlementJournalId(result.getJournalEntryId());
        transaction.setStatus("FAILED");
        transaction.setFailureReason(failureReason);
        transaction.setSettlementDate(LocalDateTime.now());
        clearingRepository.save(transaction);

        log.warn("Clearing transaction failed: ref={}, reason={}", clearingRef, failureReason);
    }

    /**
     * EOD suspense reconciliation check.
     * Validates that Clearing Suspense GL (2400) balance = 0.
     *
     * @param businessDate CBS business date
     * @return true if suspense balance is zero, false if non-zero (flagged for investigation)
     */
    @Transactional(readOnly = true)
    public boolean validateSuspenseBalance(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        var glAccount = glRepository.findByTenantIdAndGlCode(tenantId, "2400").orElse(null);

        if (glAccount == null) {
            log.warn("Clearing Suspense GL (2400) not found");
            return true; // Pass if GL doesn't exist
        }

        BigDecimal netBalance = glAccount.getCreditBalance().subtract(glAccount.getDebitBalance());

        if (netBalance.compareTo(BigDecimal.ZERO) != 0) {
            log.warn("Clearing Suspense GL (2400) non-zero at EOD: {}. Investigate stuck transactions.", netBalance);
            return false;
        }

        log.info("Clearing Suspense GL (2400) verified zero at EOD");
        return true;
    }
}
