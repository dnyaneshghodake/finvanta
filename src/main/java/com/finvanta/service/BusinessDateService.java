package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.Tenant;
import com.finvanta.domain.entity.TransactionBatch;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.TenantRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Business Date Service — Single source of truth for the current business date.
 *
 * Per Finacle DAYCTRL / Temenos COB Day Control standards:
 * - The business date is NOT the system date
 * - Business date is determined by which day is in DAY_OPEN status PER BRANCH
 * - All financial transactions, journal entries, and batch operations must use this date
 * - System date may be ahead of business date (e.g., EOD runs after midnight)
 *
 * Tier-1 Branch-Level Day Control:
 * - Each branch independently opens/closes its day
 * - Branch A can be DAY_OPEN while Branch B is DAY_CLOSED
 * - HO consolidation runs after ALL branches complete EOD
 *
 * Day lifecycle per branch:
 *   Day Open (ADMIN) → Batches Open → Transactions → Batches Close → EOD → Day Close → Next Day Open
 *
 * Example:
 *   System clock: 5 April 01:30 AM
 *   Branch 001 business date: 4 April (EOD still running)
 *   Branch 002 business date: 5 April (already opened)
 *   Transactions at Branch 001 are dated 4 April, Branch 002 dated 5 April
 */
@Service
public class BusinessDateService {

    private static final Logger log = LoggerFactory.getLogger(BusinessDateService.class);

    private final BusinessCalendarRepository calendarRepository;
    private final TransactionBatchRepository batchRepository;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public BusinessDateService(
            BusinessCalendarRepository calendarRepository,
            TransactionBatchRepository batchRepository,
            BranchRepository branchRepository,
            TenantRepository tenantRepository,
            AuditService auditService) {
        this.calendarRepository = calendarRepository;
        this.batchRepository = batchRepository;
        this.branchRepository = branchRepository;
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    /**
     * Returns the current CBS business date for a specific branch.
     * This is the ONLY method that should be used to get the business date.
     * Never use LocalDate.now() for financial operations.
     *
     * Per Finacle DAYCTRL: business date is PER BRANCH, not per tenant.
     *
     * @param branchId The branch to get business date for
     * @return Current business date at the specified branch
     * @throws BusinessException if no day is open at the branch
     */
    public LocalDate getCurrentBusinessDate(Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        return calendarRepository
                .findOpenDayByBranch(tenantId, branchId)
                .map(BusinessCalendar::getBusinessDate)
                .orElseThrow(() -> new BusinessException(
                        "NO_OPEN_DAY",
                        "No business day is currently open at branch " + branchId
                                + ". Contact administrator to open the day."));
    }

    /**
     * Returns the current CBS business date using the current user's branch.
     * Convenience method for user-initiated operations.
     *
     * @return Current business date at the user's home branch
     * @throws BusinessException if no day is open or user has no branch
     */
    public LocalDate getCurrentBusinessDate() {
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId == null) {
            throw new BusinessException(
                    "BRANCH_NOT_ASSIGNED",
                    "Cannot determine business date — no branch assigned to current user.");
        }
        return getCurrentBusinessDate(branchId);
    }

    /**
     * Returns the current open day calendar entry for a branch, or null if no day is open.
     * Used by controllers to display business date without throwing exceptions.
     *
     * @param branchId The branch to check
     */
    public BusinessCalendar getOpenDayOrNull(Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        return calendarRepository.findOpenDayByBranch(tenantId, branchId).orElse(null);
    }

    /**
     * Returns the current open day using the current user's branch, or null.
     */
    public BusinessCalendar getOpenDayOrNull() {
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId == null) {
            return null;
        }
        return getOpenDayOrNull(branchId);
    }

    /**
     * Opens a business day at a specific branch with CBS SOD (Start-of-Day) validations.
     *
     * Per Finacle DAYCTRL / Temenos COB Day Control:
     * 1. No other day can be currently open at THIS BRANCH (single open day per branch)
     * 2. The date must exist in the calendar for this branch and not be a holiday
     * 3. The previous business day at THIS BRANCH must be in DAY_CLOSED status
     * 4. A default transaction batch is auto-created for the business date
     *
     * @param businessDate The date to open
     * @param branchId     The branch to open the day for
     * @return The opened calendar entry
     */
    @Transactional
    public BusinessCalendar openDay(LocalDate businessDate, Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        Branch branch = branchRepository
                .findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found: " + branchId));

        // Validate: no other day is currently open at this branch
        calendarRepository.findOpenDayByBranch(tenantId, branchId).ifPresent(openDay -> {
            throw new BusinessException(
                    "DAY_ALREADY_OPEN",
                    "Business date " + openDay.getBusinessDate()
                            + " is already open at branch " + branch.getBranchCode()
                            + ". Close it before opening a new day.");
        });

        BusinessCalendar calendar = calendarRepository
                .findAndLockByTenantIdAndBranchIdAndDate(tenantId, branchId, businessDate)
                .orElseThrow(() -> new BusinessException(
                        "DATE_NOT_IN_CALENDAR",
                        "Business date " + businessDate + " not found in calendar for branch "
                                + branch.getBranchCode() + ". Generate the calendar first."));

        if (calendar.isDayOpen()) {
            throw new BusinessException(
                    "DAY_ALREADY_OPEN",
                    "Business date " + businessDate + " is already open at branch " + branch.getBranchCode() + ".");
        }

        if (calendar.isDayClosed()) {
            throw new BusinessException(
                    "DAY_ALREADY_CLOSED",
                    "Business date " + businessDate + " is already closed at branch "
                            + branch.getBranchCode() + ". Cannot reopen.");
        }

        if (calendar.isHoliday()) {
            throw new BusinessException(
                    "DAY_IS_HOLIDAY",
                    "Cannot open a holiday at branch " + branch.getBranchCode() + ": " + businessDate);
        }

        // CBS SOD Validation: Previous business day at THIS BRANCH must be DAY_CLOSED.
        validatePreviousDayClosed(tenantId, branchId, businessDate);

        calendar.setDayStatus(DayStatus.DAY_OPEN);
        calendar.setDayOpenedBy(currentUser);
        calendar.setDayOpenedAt(LocalDateTime.now());
        calendar.setUpdatedBy(currentUser);

        BusinessCalendar saved = calendarRepository.save(calendar);

        // CBS SOD: Auto-create a default INTRA_DAY transaction batch for the business date.
        // Per Finacle BATCH_MASTER: each branch gets its own default batch.
        // Check uses branch-specific batch name to prevent second branch skipping batch creation.
        String batchName = "DEFAULT_BATCH_" + branch.getBranchCode();
        if (!batchRepository.existsByTenantIdAndBusinessDateAndBatchName(tenantId, businessDate, batchName)) {
            TransactionBatch defaultBatch = new TransactionBatch();
            defaultBatch.setTenantId(tenantId);
            defaultBatch.setBusinessDate(businessDate);
            defaultBatch.setBatchName(batchName);
            defaultBatch.setBatchType("INTRA_DAY");
            defaultBatch.setStatus("OPEN");
            defaultBatch.setOpenedBy(currentUser);
            defaultBatch.setOpenedAt(LocalDateTime.now());
            defaultBatch.setMakerId(currentUser);
            defaultBatch.setCreatedBy(currentUser);
            defaultBatch.setBranch(branch);
            batchRepository.save(defaultBatch);
            log.info("Default transaction batch auto-created for {} at branch {}", businessDate, branch.getBranchCode());
        }

        auditService.logEvent(
                "BusinessCalendar",
                saved.getId(),
                "DAY_OPEN",
                "NOT_OPENED",
                "DAY_OPEN",
                "DAY_CONTROL",
                "Business day opened: " + businessDate + " at branch " + branch.getBranchCode() + " by " + currentUser);

        log.info("Business day opened: date={}, branch={}, user={}", businessDate, branch.getBranchCode(), currentUser);

        return saved;
    }

    /**
     * Backward-compatible openDay using current user's branch.
     * @deprecated Use {@link #openDay(LocalDate, Long)} with explicit branchId.
     */
    @Deprecated(forRemoval = true)
    @Transactional
    public BusinessCalendar openDay(LocalDate businessDate) {
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId == null) {
            throw new BusinessException(
                    "BRANCH_NOT_ASSIGNED", "Cannot open day — no branch assigned to current user.");
        }
        return openDay(businessDate, branchId);
    }

    /**
     * Closes a business day at a specific branch. EOD must be complete before closing.
     *
     * @param businessDate The date to close
     * @param branchId     The branch to close the day for
     * @return The closed calendar entry
     */
    @Transactional
    public BusinessCalendar closeDay(LocalDate businessDate, Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        BusinessCalendar calendar = calendarRepository
                .findAndLockByTenantIdAndBranchIdAndDate(tenantId, branchId, businessDate)
                .orElseThrow(() -> new BusinessException(
                        "DATE_NOT_IN_CALENDAR",
                        "Business date " + businessDate + " not found in calendar for branch " + branchId + "."));

        if (!calendar.getDayStatus().canClose()) {
            throw new BusinessException(
                    "DAY_NOT_OPEN",
                    "Business date " + businessDate + " at branch " + calendar.getBranchCode()
                            + " is not in a closeable state. Current: " + calendar.getDayStatus());
        }

        if (!calendar.isEodComplete()) {
            throw new BusinessException(
                    "EOD_NOT_COMPLETE",
                    "Cannot close day " + businessDate + " at branch " + calendar.getBranchCode()
                            + " — EOD has not completed successfully.");
        }

        calendar.setDayStatus(DayStatus.DAY_CLOSED);
        calendar.setDayClosedBy(currentUser);
        calendar.setDayClosedAt(LocalDateTime.now());
        calendar.setLocked(false);
        calendar.setUpdatedBy(currentUser);

        BusinessCalendar saved = calendarRepository.save(calendar);

        auditService.logEvent(
                "BusinessCalendar",
                saved.getId(),
                "DAY_CLOSE",
                "DAY_OPEN",
                "DAY_CLOSED",
                "DAY_CONTROL",
                "Business day closed: " + businessDate + " at branch " + calendar.getBranchCode()
                        + " by " + currentUser);

        log.info("Business day closed: date={}, branch={}, user={}",
                businessDate, calendar.getBranchCode(), currentUser);

        return saved;
    }

    /**
     * Backward-compatible closeDay using current user's branch.
     * @deprecated Use {@link #closeDay(LocalDate, Long)} with explicit branchId.
     */
    @Deprecated(forRemoval = true)
    @Transactional
    public BusinessCalendar closeDay(LocalDate businessDate) {
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId == null) {
            throw new BusinessException(
                    "BRANCH_NOT_ASSIGNED", "Cannot close day — no branch assigned to current user.");
        }
        return closeDay(businessDate, branchId);
    }

    // ========================================================================
    // P1 Gap 4.1/4.2: CALENDAR GENERATION (per Finacle DAYCTRL / Temenos COB)
    // ========================================================================

    /**
     * Generate business calendar for a month for ALL operational branches.
     *
     * Per Finacle DAYCTRL / RBI NI Act / Tier-1 Branch-Level Day Control:
     *   - Every date in the month gets a calendar entry PER BRANCH
     *   - Saturdays and Sundays are auto-marked as holidays for all branches
     *   - Additional holidays (gazetted/state) can be added separately via addHoliday()
     *   - Existing entries are skipped (idempotent — safe to call multiple times)
     *
     * @param year  Calendar year (e.g., 2026)
     * @param month Calendar month (1-12)
     * @return Number of new calendar entries created (across all branches)
     */
    @Transactional
    public int generateCalendarForMonth(int year, int month) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        YearMonth yearMonth = YearMonth.of(year, month);
        int daysInMonth = yearMonth.lengthOfMonth();
        int created = 0;

        // Per Finacle: calendar entries are created for EVERY operational branch
        List<Branch> operationalBranches = branchRepository.findAllOperationalBranches(tenantId);
        if (operationalBranches.isEmpty()) {
            throw new BusinessException(
                    "NO_OPERATIONAL_BRANCHES",
                    "No operational branches found for tenant " + tenantId
                            + ". Create at least one BRANCH-type branch first.");
        }

        // CBS Tier-1: Resolve tenant's business day policy for weekend detection.
        // Per Finacle BANK_PARAM.WORKING_DAYS / Temenos COMPANY.WORKING.DAYS:
        //   MON_TO_SAT → Only Sunday is weekend (most Indian banks)
        //   MON_TO_FRI → Saturday and Sunday are weekends
        // Default: MON_TO_SAT per RBI NI Act (Indian banking standard)
        String businessDayPolicy = "MON_TO_SAT";
        Tenant tenant = tenantRepository.findByTenantCode(tenantId).orElse(null);
        if (tenant != null && tenant.getBusinessDayPolicy() != null
                && !tenant.getBusinessDayPolicy().isBlank()) {
            businessDayPolicy = tenant.getBusinessDayPolicy();
        }
        boolean saturdayIsWeekend = "MON_TO_FRI".equals(businessDayPolicy);

        for (Branch branch : operationalBranches) {
            for (int day = 1; day <= daysInMonth; day++) {
                LocalDate date = LocalDate.of(year, month, day);

                // Skip if entry already exists for this branch+date (idempotent)
                if (calendarRepository
                        .findByTenantIdAndBranchIdAndBusinessDate(tenantId, branch.getId(), date)
                        .isPresent()) {
                    continue;
                }

                BusinessCalendar cal = new BusinessCalendar();
                cal.setTenantId(tenantId);
                cal.setBranch(branch);
                cal.setBranchCode(branch.getBranchCode());
                cal.setBusinessDate(date);
                cal.setDayStatus(DayStatus.NOT_OPENED);
                cal.setEodComplete(false);
                cal.setLocked(false);
                cal.setCreatedBy(currentUser);

                // Auto-detect weekends per RBI NI Act + tenant business day policy
                DayOfWeek dow = date.getDayOfWeek();
                if (dow == DayOfWeek.SUNDAY) {
                    cal.setHoliday(true);
                    cal.setHolidayDescription("Sunday");
                    cal.setHolidayType("WEEKEND");
                } else if (dow == DayOfWeek.SATURDAY && saturdayIsWeekend) {
                    cal.setHoliday(true);
                    cal.setHolidayDescription("Saturday");
                    cal.setHolidayType("WEEKEND");
                } else {
                    cal.setHoliday(false);
                }

                calendarRepository.save(cal);
                created++;
            }
        }

        if (created > 0) {
            auditService.logEvent(
                    "BusinessCalendar",
                    null,
                    "CALENDAR_GENERATED",
                    null,
                    year + "-" + String.format("%02d", month),
                    "DAY_CONTROL",
                    "Calendar generated for " + yearMonth + ": " + created + " entries across "
                            + operationalBranches.size() + " branches by " + currentUser);
            log.info("Calendar generated: month={}, entries={}, branches={}, user={}",
                    yearMonth, created, operationalBranches.size(), currentUser);
        }

        return created;
    }

    /**
     * Add a holiday to an existing calendar date for ALL operational branches.
     * Per RBI NI Act: holiday list must be configurable per state/region.
     *
     * For NATIONAL/WEEKEND holidays: applied to ALL branches.
     * For STATE holidays: applied only to branches in the matching region.
     *
     * @param date         The date to mark as holiday
     * @param description  Holiday description (mandatory per RBI NI Act)
     * @param holidayType  NATIONAL, STATE, BANK, OPTIONAL, GAZETTED (null defaults to GAZETTED)
     * @param region       Applicable region/state for STATE holidays (null for NATIONAL)
     */
    @Transactional
    public void addHoliday(LocalDate date, String description, String holidayType, String region) {
        String tenantId = TenantContext.getCurrentTenant();
        String resolvedType = holidayType != null ? holidayType : "GAZETTED";

        // Determine which branches to apply the holiday to
        List<Branch> targetBranches;
        if ("STATE".equals(resolvedType) && region != null) {
            // STATE holidays apply only to branches in the matching region
            targetBranches = branchRepository.findBranchesByRegion(tenantId, region);
        } else {
            // NATIONAL, WEEKEND, BANK, OPTIONAL, GAZETTED apply to all operational branches
            targetBranches = branchRepository.findAllOperationalBranches(tenantId);
        }

        int updated = 0;
        for (Branch branch : targetBranches) {
            BusinessCalendar cal = calendarRepository
                    .findByTenantIdAndBranchIdAndBusinessDate(tenantId, branch.getId(), date)
                    .orElse(null);

            if (cal == null) {
                continue; // Calendar not yet generated for this branch+date
            }

            if (cal.isDayOpen() || cal.isDayClosed()) {
                log.warn("Cannot mark {} as holiday at branch {} — day is already {}",
                        date, branch.getBranchCode(), cal.getDayStatus());
                continue;
            }

            cal.setHoliday(true);
            cal.setHolidayDescription(description);
            cal.setHolidayType(resolvedType);
            cal.setHolidayRegion(region);
            cal.setUpdatedBy(SecurityUtil.getCurrentUsername());
            calendarRepository.save(cal);
            updated++;
        }

        auditService.logEvent(
                "BusinessCalendar",
                null,
                "HOLIDAY_ADDED",
                null,
                description,
                "DAY_CONTROL",
                "Holiday added: " + date + " — " + description
                        + " | Type: " + resolvedType
                        + (region != null ? " | Region: " + region : "")
                        + " | Branches: " + updated
                        + " by " + SecurityUtil.getCurrentUsername());
        log.info("Holiday added: date={}, description={}, type={}, region={}, branches={}",
                date, description, resolvedType, region, updated);
    }

    /**
     * Backward-compatible addHoliday without type/region (defaults to GAZETTED).
     */
    @Transactional
    public void addHoliday(LocalDate date, String description) {
        addHoliday(date, description, "GAZETTED", null);
    }

    // ========================================================================
    // SOD VALIDATION HELPERS
    // ========================================================================

    /**
     * Validates that the previous business day at a specific branch is in DAY_CLOSED status.
     * Skips holidays and weekends.
     *
     * Per Finacle DAYCTRL: days must be processed in strict sequence PER BRANCH.
     * The first-ever day open (no previous day exists) is allowed without validation.
     */
    private void validatePreviousDayClosed(String tenantId, Long branchId, LocalDate businessDate) {
        // Walk backwards to find the most recent non-holiday calendar entry at this branch
        LocalDate checkDate = businessDate.minusDays(1);
        int maxLookback = 10; // Max days to look back (covers long weekends + holidays)

        for (int i = 0; i < maxLookback; i++) {
            var prevDay = calendarRepository.findByTenantIdAndBranchIdAndBusinessDate(tenantId, branchId, checkDate);
            if (prevDay.isPresent()) {
                BusinessCalendar prev = prevDay.get();
                if (!prev.isHoliday()) {
                    // Found the previous working day at this branch — must be DAY_CLOSED
                    if (!prev.isDayClosed()) {
                        throw new BusinessException(
                                "PREVIOUS_DAY_NOT_CLOSED",
                                "Cannot open " + businessDate + " at branch " + prev.getBranchCode()
                                        + " — previous business day " + checkDate + " is in "
                                        + prev.getDayStatus()
                                        + " status. Close it first (run EOD + Day Close).");
                    }
                    return; // Previous day is closed — validation passed
                }
            }
            checkDate = checkDate.minusDays(1);
        }
        // No previous working day found within lookback window — first day or calendar gap
        log.info(
                "No previous working day found within {} days of {} at branch {} — allowing day open (initial setup)",
                maxLookback,
                businessDate,
                branchId);
    }

    /**
     * Validates that a value date is within the allowed window relative to the
     * current business date. Per Finacle/Temenos: transactions can only be posted
     * with value dates within a configurable window (default T-2 to T+2).
     *
     * This prevents:
     * - Excessive back-dating that bypasses period-close controls
     * - Forward-dating beyond the current processing window
     *
     * @param valueDate    The transaction value date to validate
     * @param businessDate The current CBS business date
     * @param backDays     Maximum days back allowed (e.g., 2)
     * @param forwardDays  Maximum days forward allowed (e.g., 2)
     * @throws BusinessException if value date is outside the allowed window
     */
    public void validateValueDateWindow(LocalDate valueDate, LocalDate businessDate, int backDays, int forwardDays) {
        LocalDate earliest = businessDate.minusDays(backDays);
        LocalDate latest = businessDate.plusDays(forwardDays);

        if (valueDate.isBefore(earliest)) {
            throw new BusinessException(
                    "VALUE_DATE_TOO_OLD",
                    "Value date " + valueDate + " is before the allowed window. " + "Earliest allowed: " + earliest
                            + " (T-" + backDays + ").");
        }
        if (valueDate.isAfter(latest)) {
            throw new BusinessException(
                    "VALUE_DATE_TOO_FUTURE",
                    "Value date " + valueDate + " is beyond the allowed window. " + "Latest allowed: " + latest + " (T+"
                            + forwardDays + ").");
        }
    }

    /**
     * Remove holiday flag from a calendar date for ALL branches where it's set.
     * Per Finacle: removes the holiday from all branches (or region-specific branches).
     */
    @Transactional
    public void removeHoliday(LocalDate date) {
        String tenantId = TenantContext.getCurrentTenant();
        List<Branch> operationalBranches = branchRepository.findAllOperationalBranches(tenantId);
        int updated = 0;

        for (Branch branch : operationalBranches) {
            BusinessCalendar cal = calendarRepository
                    .findByTenantIdAndBranchIdAndBusinessDate(tenantId, branch.getId(), date)
                    .orElse(null);

            if (cal == null || !cal.isHoliday()) {
                continue;
            }

            if (cal.isDayOpen() || cal.isDayClosed()) {
                log.warn("Cannot remove holiday {} at branch {} — day is already {}",
                        date, branch.getBranchCode(), cal.getDayStatus());
                continue;
            }

            String prevDescription = cal.getHolidayDescription();
            cal.setHoliday(false);
            cal.setHolidayDescription(null);
            cal.setHolidayType(null);
            cal.setHolidayRegion(null);
            cal.setUpdatedBy(SecurityUtil.getCurrentUsername());
            calendarRepository.save(cal);
            updated++;
        }

        auditService.logEvent(
                "BusinessCalendar",
                null,
                "HOLIDAY_REMOVED",
                null,
                null,
                "DAY_CONTROL",
                "Holiday removed: " + date + " | Branches: " + updated
                        + " by " + SecurityUtil.getCurrentUsername());
    }
}
