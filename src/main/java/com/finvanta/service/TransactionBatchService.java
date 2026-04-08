package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.TransactionBatch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CBS Enterprise Transaction Batch Service per Finacle/Temenos standards.
 *
 * Manages the batch lifecycle:
 *   OPEN → TRANSACTIONS → CLOSE → EOD
 *
 * Key CBS rules enforced:
 * 1. No transaction posting without an OPEN batch
 * 2. EOD blocked if any batch is still OPEN
 * 3. Closed batches are immutable — cannot be reopened
 * 4. Batch debit must equal credit at close (double-entry integrity)
 * 5. Duplicate batch names per business date are rejected
 * 6. System batches (EOD) are auto-opened/closed by the batch engine
 *
 * Batch types:
 *   INTRA_DAY  — Manual branch operations (MAKER opens, CHECKER approves close)
 *   CLEARING   — Settlement cycle batches (RTGS/NEFT/IMPS)
 *   SYSTEM     — EOD automated batches (opened/closed by BatchService)
 */
@Service
public class TransactionBatchService {

    private static final Logger log = LoggerFactory.getLogger(TransactionBatchService.class);

    private final TransactionBatchRepository batchRepository;
    private final BusinessCalendarRepository calendarRepository;
    private final BranchRepository branchRepository;
    private final AuditService auditService;

    public TransactionBatchService(TransactionBatchRepository batchRepository,
                                    BusinessCalendarRepository calendarRepository,
                                    BranchRepository branchRepository,
                                    AuditService auditService) {
        this.batchRepository = batchRepository;
        this.calendarRepository = calendarRepository;
        this.branchRepository = branchRepository;
        this.auditService = auditService;
    }

    /**
     * Opens a new transaction batch for a business date.
     *
     * @param businessDate CBS business date (from calendar, not system date)
     * @param batchName    Unique name: MORNING_BATCH, AFTERNOON_BATCH, etc.
     * @param batchType    INTRA_DAY, CLEARING, or SYSTEM
     * @param branchId     Branch ID (null for tenant-level/system batches)
     * @return The opened batch
     */
    @Transactional
    public TransactionBatch openBatch(LocalDate businessDate, String batchName,
                                       String batchType, Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // CBS CRITICAL: Business day must be DAY_OPEN before any batch can be opened.
        // Per Finacle DAYCTRL / Temenos COB: batches are only valid for an open business day.
        // Without this check, batches could be opened for NOT_OPENED, DAY_CLOSED, or
        // holiday dates — breaking the CBS day control lifecycle invariant.
        BusinessCalendar calendar = calendarRepository
            .findByTenantIdAndBusinessDate(tenantId, businessDate)
            .orElseThrow(() -> new BusinessException("DATE_NOT_IN_CALENDAR",
                "Business date " + businessDate + " not found in calendar."));

        if (!calendar.isDayOpen()) {
            throw new BusinessException("DAY_NOT_OPEN",
                "Cannot open batch for " + businessDate
                    + " — day status is " + calendar.getDayStatus()
                    + ". The business day must be DAY_OPEN before opening transaction batches.");
        }

        if (calendar.isHoliday()) {
            throw new BusinessException("DAY_IS_HOLIDAY",
                "Cannot open batch for holiday: " + businessDate);
        }

        // Validate: no duplicate batch name for same business date
        if (batchRepository.existsByTenantIdAndBusinessDateAndBatchName(
                tenantId, businessDate, batchName)) {
            throw new BusinessException("BATCH_DUPLICATE",
                "Batch '" + batchName + "' already exists for business date " + businessDate);
        }

        TransactionBatch batch = new TransactionBatch();
        batch.setTenantId(tenantId);
        batch.setBusinessDate(businessDate);
        batch.setBatchName(batchName);
        batch.setBatchType(batchType);
        batch.setStatus("OPEN");
        batch.setOpenedBy(currentUser);
        batch.setOpenedAt(LocalDateTime.now());
        batch.setMakerId(currentUser);
        batch.setCreatedBy(currentUser);

        if (branchId != null) {
            Branch branch = branchRepository.findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND",
                    "Branch not found: " + branchId));
            batch.setBranch(branch);
        }

        TransactionBatch saved = batchRepository.save(batch);

        auditService.logEvent("TransactionBatch", saved.getId(), "OPEN",
            null, saved.getBatchName(), "BATCH_CONTROL",
            "Batch opened: " + batchName + " (" + batchType + ") for " + businessDate);

        log.info("Batch opened: name={}, type={}, date={}, user={}",
            batchName, batchType, businessDate, currentUser);

        return saved;
    }

    /**
     * Closes a transaction batch with CBS validation:
     * 1. Batch must be OPEN
     * 2. Total debit must equal total credit (double-entry integrity)
     * 3. Closed batch becomes immutable
     */
    @Transactional
    public TransactionBatch closeBatch(Long batchId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        TransactionBatch batch = batchRepository.findAndLockById(batchId)
            .filter(b -> b.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("BATCH_NOT_FOUND",
                "Transaction batch not found: " + batchId));

        if (!batch.isOpen()) {
            throw new BusinessException("BATCH_NOT_OPEN",
                "Batch '" + batch.getBatchName() + "' is not in OPEN status. Current: " + batch.getStatus());
        }

        // CBS double-entry integrity: debit must equal credit
        if (!batch.isBalanced()) {
            throw new BusinessException("BATCH_IMBALANCED",
                "Cannot close batch '" + batch.getBatchName() + "': Total Debit ("
                    + batch.getTotalDebit() + ") != Total Credit (" + batch.getTotalCredit() + ")");
        }

        batch.setStatus("CLOSED");
        batch.setClosedBy(currentUser);
        batch.setClosedAt(LocalDateTime.now());
        batch.setCheckerId(currentUser);
        batch.setUpdatedBy(currentUser);

        TransactionBatch saved = batchRepository.save(batch);

        auditService.logEvent("TransactionBatch", saved.getId(), "CLOSE",
            "OPEN", "CLOSED", "BATCH_CONTROL",
            "Batch closed: " + batch.getBatchName() + ", txns=" + batch.getTotalTransactions()
                + ", debit=" + batch.getTotalDebit() + ", credit=" + batch.getTotalCredit());

        log.info("Batch closed: name={}, txns={}, debit={}, credit={}",
            batch.getBatchName(), batch.getTotalTransactions(),
            batch.getTotalDebit(), batch.getTotalCredit());

        return saved;
    }

    /**
     * Validates that at least one OPEN batch exists for the business date.
     * Called by the transaction engine before posting any financial transaction.
     *
     * @param businessDate CBS business date
     * @return The first available OPEN batch
     * @throws BusinessException if no OPEN batch exists
     */
    public TransactionBatch requireOpenBatch(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        List<TransactionBatch> openBatches = batchRepository.findOpenBatches(tenantId, businessDate);

        if (openBatches.isEmpty()) {
            throw new BusinessException("NO_OPEN_BATCH",
                "No OPEN transaction batch for business date " + businessDate
                    + ". Open a batch before posting transactions.");
        }

        return openBatches.get(0);
    }

    /**
     * Validates that ALL batches for the business date are CLOSED.
     * Called by EOD engine before starting EOD processing.
     *
     * @param businessDate CBS business date
     * @throws BusinessException if any batch is still OPEN
     */
    public void validateAllBatchesClosed(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        long openCount = batchRepository.countOpenBatches(tenantId, businessDate);

        if (openCount > 0) {
            throw new BusinessException("BATCHES_STILL_OPEN",
                openCount + " batch(es) still OPEN for business date " + businessDate
                    + ". Close all batches before running EOD.");
        }
    }

    /**
     * Returns all batches for a business date (for batch management UI).
     */
    public List<TransactionBatch> getBatchesForDate(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        return batchRepository.findByTenantIdAndBusinessDateOrderByOpenedAtAsc(
            tenantId, businessDate);
    }

    /**
     * Returns all OPEN batches for a business date.
     */
    public List<TransactionBatch> getOpenBatches(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        return batchRepository.findOpenBatches(tenantId, businessDate);
    }
}