package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.InterBranchTransaction;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.InterBranchSettlementRepository;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Inter-Branch Settlement Service per Finacle IB_SETTLEMENT.
 *
 * Records and settles inter-branch fund transfers with dual GL posting:
 * - Source branch: DR Customer / CR Inter-Branch Payable (GL 2300)
 * - Target branch: DR Inter-Branch Receivable (GL 1300) / CR Customer
 *
 * Settlement occurs during EOD: sums branch receivables and payables.
 * If balanced (sum(receivables) = sum(payables)), status = SETTLED.
 * If not balanced, status = FAILED and logged for investigation.
 */
@Service
public class InterBranchSettlementService {

    private static final Logger log = LoggerFactory.getLogger(InterBranchSettlementService.class);

    private final InterBranchSettlementRepository settlementRepository;
    private final BranchRepository branchRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final TransactionEngine transactionEngine;

    public InterBranchSettlementService(
            InterBranchSettlementRepository settlementRepository,
            BranchRepository branchRepository,
            LedgerEntryRepository ledgerRepository,
            TransactionEngine transactionEngine) {
        this.settlementRepository = settlementRepository;
        this.branchRepository = branchRepository;
        this.ledgerRepository = ledgerRepository;
        this.transactionEngine = transactionEngine;
    }

    /**
     * Record inter-branch fund transfer with dual GL posting.
     *
     * @param sourceBranchId Source branch ID
     * @param targetBranchId Target branch ID
     * @param amount Transfer amount
     * @param businessDate CBS business date
     */
    @Transactional
    public InterBranchTransaction recordInterBranchTransfer(
            Long sourceBranchId, Long targetBranchId, BigDecimal amount, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Transfer amount must be positive");
        }

        Branch sourceBranch = branchRepository
                .findById(sourceBranchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(
                        () -> new BusinessException("BRANCH_NOT_FOUND", "Source branch not found: " + sourceBranchId));
        Branch targetBranch = branchRepository
                .findById(targetBranchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(
                        () -> new BusinessException("BRANCH_NOT_FOUND", "Target branch not found: " + targetBranchId));

        // CBS: Create inter-branch transaction record
        InterBranchTransaction transaction = new InterBranchTransaction();
        transaction.setTenantId(tenantId);
        transaction.setSourceBranch(sourceBranch);
        transaction.setTargetBranch(targetBranch);
        transaction.setAmount(amount);
        transaction.setSettlementStatus("PENDING");
        transaction.setBusinessDate(businessDate);

        InterBranchTransaction savedTransaction = settlementRepository.save(transaction);

        String sourceCode = sourceBranch.getBranchCode();
        String targetCode = targetBranch.getBranchCode();

        // CBS: Post dual GL entries via TransactionEngine
        // Source branch: DR Customer / CR Inter-Branch Payable
        List<JournalLineRequest> sourceLines = List.of(
                new JournalLineRequest(
                        "1100", DebitCredit.DEBIT, amount, "Inter-branch transfer from branch " + sourceCode),
                new JournalLineRequest(
                        "2300", DebitCredit.CREDIT, amount, "Inter-branch payable to branch " + targetCode));

        TransactionResult sourceResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("INTER_BRANCH")
                .transactionType("IB_TRANSFER_SOURCE")
                .accountReference("IB-" + sourceCode + "-" + targetCode)
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(sourceCode)
                .narration("Inter-branch transfer source: " + amount + " from " + sourceCode)
                .journalLines(sourceLines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build());

        savedTransaction.setSourceJournalId(sourceResult.getJournalEntryId());

        // Target branch: DR Inter-Branch Receivable / CR Customer
        List<JournalLineRequest> targetLines = List.of(
                new JournalLineRequest(
                        "1300", DebitCredit.DEBIT, amount, "Inter-branch receivable from branch " + sourceCode),
                new JournalLineRequest(
                        "1100",
                        DebitCredit.CREDIT,
                        amount,
                        "Inter-branch transfer received from branch " + sourceCode));

        TransactionResult targetResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("INTER_BRANCH")
                .transactionType("IB_TRANSFER_TARGET")
                .accountReference("IB-" + sourceCode + "-" + targetCode)
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(targetCode)
                .narration("Inter-branch transfer target: " + amount + " to " + targetCode)
                .journalLines(targetLines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build());

        savedTransaction.setTargetJournalId(targetResult.getJournalEntryId());
        settlementRepository.save(savedTransaction);

        log.info(
                "Inter-branch transfer recorded: source={}, target={}, amount={}, status=PENDING",
                sourceCode,
                targetCode,
                amount);

        return savedTransaction;
    }

    /**
     * Settle inter-branch transactions (called during EOD).
     *
     * Per Finacle IB_SETTLEMENT / Temenos IB.NETTING:
     * 1. Validate all pending IB transactions have complete GL postings
     * 2. Calculate net position per branch (receivables - payables)
     * 3. Mark transactions as SETTLED with batch reference
     * 4. Log net position summary for HO reconciliation
     *
     * @param businessDate CBS business date
     */
    @Transactional
    public void settleInterBranch(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        List<InterBranchTransaction> pendingTransactions =
                settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(tenantId, "PENDING");

        if (pendingTransactions.isEmpty()) {
            log.info("No pending inter-branch transactions to settle for {}", businessDate);
            return;
        }

        String settlementBatchRef = "IB-SETTLEMENT-" + businessDate;
        boolean allBalanced = true;

        // CBS Tier-1: Calculate per-branch net position (receivables - payables)
        java.util.Map<String, java.math.BigDecimal> branchNetPositions = new java.util.LinkedHashMap<>();

        for (InterBranchTransaction txn : pendingTransactions) {
            try {
                if (txn.getSourceJournalId() != null && txn.getTargetJournalId() != null) {
                    txn.setSettlementStatus("SETTLED");
                    txn.setSettlementBatchRef(settlementBatchRef);

                    // Track net position: source branch pays (negative), target branch receives (positive)
                    String sourceCode = txn.getSourceBranch().getBranchCode();
                    String targetCode = txn.getTargetBranch().getBranchCode();
                    branchNetPositions.merge(sourceCode, txn.getAmount().negate(), java.math.BigDecimal::add);
                    branchNetPositions.merge(targetCode, txn.getAmount(), java.math.BigDecimal::add);
                } else {
                    txn.setSettlementStatus("FAILED");
                    txn.setFailureReason("Incomplete GL posting — source or target journal missing");
                    allBalanced = false;
                }
            } catch (Exception e) {
                txn.setSettlementStatus("FAILED");
                txn.setFailureReason(e.getMessage());
                allBalanced = false;
                log.error("Inter-branch settlement failed for transaction {}: {}", txn.getId(), e.getMessage());
            }
            settlementRepository.save(txn);
        }

        // CBS: Verify net positions sum to zero (conservation of funds)
        java.math.BigDecimal totalNetPosition = branchNetPositions.values().stream()
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        if (totalNetPosition.compareTo(java.math.BigDecimal.ZERO) != 0) {
            log.error("IB SETTLEMENT IMBALANCE: net positions do not sum to zero! Total: {}", totalNetPosition);
            allBalanced = false;
        }

        // Log per-branch net positions for HO reconciliation
        for (var entry : branchNetPositions.entrySet()) {
            String direction = entry.getValue().signum() >= 0 ? "NET_RECEIVABLE" : "NET_PAYABLE";
            log.info("IB Net Position: branch={}, amount={}, direction={}",
                    entry.getKey(), entry.getValue().abs(), direction);
        }

        if (!allBalanced) {
            log.warn("Inter-branch settlement completed with errors for {}. Manual review required.", businessDate);
        } else {
            log.info("Inter-branch settlement completed: date={}, transactions={}, branches={}",
                    businessDate, pendingTransactions.size(), branchNetPositions.size());
        }
    }
}
