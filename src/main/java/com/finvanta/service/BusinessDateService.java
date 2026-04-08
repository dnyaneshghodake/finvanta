package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.TransactionBatch;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Business Date Service — Single source of truth for the current business date.
 *
 * Per Finacle/Temenos Day Control standards:
 * - The business date is NOT the system date
 * - Business date is determined by which day is in DAY_OPEN status
 * - All financial transactions, journal entries, and batch operations must use this date
 * - System date may be ahead of business date (e.g., EOD runs after midnight)
 *
 * Day lifecycle:
 *   Day Open (ADMIN) → Batches Open → Transactions → Batches Close → EOD → Day Close → Next Day Open
 *
 * Example:
 *   System clock: 5 April 01:30 AM
 *   Business date: 4 April (EOD still running)
 *   All transactions posted at 01:30 AM are dated 4 April, not 5 April
 */
@Service
public class BusinessDateService {

    private static final Logger log = LoggerFactory.getLogger(BusinessDateService.class);

    private final BusinessCalendarRepository calendarRepository;
    private final TransactionBatchRepository batchRepository;
    private final AuditService auditService;

    public BusinessDateService(
            BusinessCalendarRepository calendarRepository,
            TransactionBatchRepository batchRepository,
            AuditService auditService) {
        this.calendarRepository = calendarRepository;
        this.batchRepository = batchRepository;
        this.auditService = auditService;
    }

    /**
     * Returns the current CBS business date — the date with DAY_OPEN status.
     * This is the ONLY method that should be used to get the business date.
     * Never use LocalDate.now() for financial operations.
     *
     * @return Current business date
     * @throws BusinessException if no day is open
     */
    public LocalDate getCurrentBusinessDate() {
        String tenantId = TenantContext.getCurrentTenant();
        return calendarRepository
                .findOpenDay(tenantId)
                .map(BusinessCalendar::getBusinessDate)
                .orElseThrow(() -> new BusinessException(
                        "NO_OPEN_DAY", "No business day is currently open. Contact administrator to open the day."));
    }

    /**
     * Returns the current open day calendar entry, or null if no day is open.
     * Used by controllers to display business date without throwing exceptions.
     */
    public BusinessCalendar getOpenDayOrNull() {
        String tenantId = TenantContext.getCurrentTenant();
        return calendarRepository.findOpenDay(tenantId).orElse(null);
    }

    /**
     * Opens a business day with CBS SOD (Start-of-Day) validations.
     *
     * Per Finacle DAYCTRL / Temenos COB Day Control:
     * 1. No other day can be currently open (single open day per tenant)
     * 2. The date must exist in the calendar and not be a holiday
     * 3. The previous business day must be in DAY_CLOSED status (sequence integrity)
     * 4. A default transaction batch is auto-created for the business date
     *
     * @param businessDate The date to open
     * @return The opened calendar entry
     */
    @Transactional
    public BusinessCalendar openDay(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // Validate: no other day is currently open
        calendarRepository.findOpenDay(tenantId).ifPresent(openDay -> {
            throw new BusinessException(
                    "DAY_ALREADY_OPEN",
                    "Business date " + openDay.getBusinessDate()
                            + " is already open. Close it before opening a new day.");
        });

        BusinessCalendar calendar = calendarRepository
                .findAndLockByTenantIdAndDate(tenantId, businessDate)
                .orElseThrow(() -> new BusinessException(
                        "DATE_NOT_IN_CALENDAR",
                        "Business date " + businessDate + " not found in calendar. Add it first."));

        if (calendar.isDayOpen()) {
            throw new BusinessException("DAY_ALREADY_OPEN", "Business date " + businessDate + " is already open.");
        }

        if (calendar.isDayClosed()) {
            throw new BusinessException(
                    "DAY_ALREADY_CLOSED", "Business date " + businessDate + " is already closed. Cannot reopen.");
        }

        if (calendar.isHoliday()) {
            throw new BusinessException("DAY_IS_HOLIDAY", "Cannot open a holiday: " + businessDate);
        }

        // CBS SOD Validation: Previous business day must be DAY_CLOSED.
        // Per Finacle DAYCTRL: days must be processed in sequence — cannot skip days
        // or open a future day while the previous day is still open/not-opened.
        // This prevents: (a) gaps in the day sequence, (b) two days being processed
        // simultaneously, (c) EOD being skipped for a day.
        // We find the most recent non-holiday date before this one and verify it's closed.
        validatePreviousDayClosed(tenantId, businessDate);

        calendar.setDayStatus(DayStatus.DAY_OPEN);
        calendar.setDayOpenedBy(currentUser);
        calendar.setDayOpenedAt(LocalDateTime.now());
        calendar.setUpdatedBy(currentUser);

        BusinessCalendar saved = calendarRepository.save(calendar);

        // CBS SOD: Auto-create a default INTRA_DAY transaction batch for the business date.
        // Per Finacle/Temenos Day Control: TransactionEngine Step 5.5 requires an OPEN batch
        // for all user-initiated transactions. Without this, every deposit/withdrawal/transfer
        // would fail with BATCH_NOT_OPEN until an admin manually opens a batch.
        // Auto-creating on Day Open ensures seamless SOD operations.
        if (!batchRepository.existsByTenantIdAndBusinessDate(tenantId, businessDate)) {
            TransactionBatch defaultBatch = new TransactionBatch();
            defaultBatch.setTenantId(tenantId);
            defaultBatch.setBusinessDate(businessDate);
            defaultBatch.setBatchName("DEFAULT_BATCH");
            defaultBatch.setBatchType("INTRA_DAY");
            defaultBatch.setStatus("OPEN");
            defaultBatch.setOpenedBy(currentUser);
            defaultBatch.setOpenedAt(LocalDateTime.now());
            defaultBatch.setMakerId(currentUser);
            defaultBatch.setCreatedBy(currentUser);
            batchRepository.save(defaultBatch);
            log.info("Default transaction batch auto-created for {}", businessDate);
        }

        auditService.logEvent(
                "BusinessCalendar",
                saved.getId(),
                "DAY_OPEN",
                "NOT_OPENED",
                "DAY_OPEN",
                "DAY_CONTROL",
                "Business day opened: " + businessDate + " by " + currentUser);

        log.info("Business day opened: date={}, user={}", businessDate, currentUser);

        return saved;
    }

    /**
     * Closes a business day. EOD must be complete before closing.
     *
     * @param businessDate The date to close
     * @return The closed calendar entry
     */
    @Transactional
    public BusinessCalendar closeDay(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        BusinessCalendar calendar = calendarRepository
                .findAndLockByTenantIdAndDate(tenantId, businessDate)
                .orElseThrow(() -> new BusinessException(
                        "DATE_NOT_IN_CALENDAR", "Business date " + businessDate + " not found in calendar."));

        if (!calendar.getDayStatus().canClose()) {
            throw new BusinessException(
                    "DAY_NOT_OPEN",
                    "Business date " + businessDate + " is not in a closeable state. Current: "
                            + calendar.getDayStatus());
        }

        if (!calendar.isEodComplete()) {
            throw new BusinessException(
                    "EOD_NOT_COMPLETE", "Cannot close day " + businessDate + " — EOD has not completed successfully.");
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
                "Business day closed: " + businessDate + " by " + currentUser);

        log.info("Business day closed: date={}, user={}", businessDate, currentUser);

        return saved;
    }

    // ========================================================================
    // P1 Gap 4.1/4.2: CALENDAR GENERATION (per Finacle DAYCTRL / Temenos COB)
    // ========================================================================

    /**
     * Generate business calendar for a month with automatic weekend detection.
     *
     * Per Finacle DAYCTRL / RBI NI Act:
     *   - Every date in the month gets a calendar entry
     *   - Saturdays and Sundays are auto-marked as holidays
     *   - Additional holidays (gazetted) can be added separately via addHoliday()
     *   - Existing entries are skipped (idempotent — safe to call multiple times)
     *
     * @param year  Calendar year (e.g., 2026)
     * @param month Calendar month (1-12)
     * @return Number of new calendar entries created
     */
    @Transactional
    public int generateCalendarForMonth(int year, int month) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        YearMonth yearMonth = YearMonth.of(year, month);
        int daysInMonth = yearMonth.lengthOfMonth();
        int created = 0;

        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = LocalDate.of(year, month, day);

            // Skip if entry already exists (idempotent)
            if (calendarRepository.findByTenantIdAndBusinessDate(tenantId, date).isPresent()) {
                continue;
            }

            BusinessCalendar cal = new BusinessCalendar();
            cal.setTenantId(tenantId);
            cal.setBusinessDate(date);
            cal.setDayStatus(DayStatus.NOT_OPENED);
            cal.setEodComplete(false);
            cal.setLocked(false);
            cal.setCreatedBy(currentUser);

            // Auto-detect weekends per RBI NI Act
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                cal.setHoliday(true);
                cal.setHolidayDescription("Saturday");
                cal.setHolidayType("WEEKEND");
            } else if (dow == DayOfWeek.SUNDAY) {
                cal.setHoliday(true);
                cal.setHolidayDescription("Sunday");
                cal.setHolidayType("WEEKEND");
            } else {
                cal.setHoliday(false);
            }

            calendarRepository.save(cal);
            created++;
        }

        if (created > 0) {
            auditService.logEvent(
                    "BusinessCalendar",
                    null,
                    "CALENDAR_GENERATED",
                    null,
                    year + "-" + String.format("%02d", month),
                    "DAY_CONTROL",
                    "Calendar generated for " + yearMonth + ": " + created + " days by " + currentUser);
            log.info("Calendar generated: month={}, days={}, user={}", yearMonth, created, currentUser);
        }

        return created;
    }

    /**
     * Add a holiday to an existing calendar date with type classification.
     * Per RBI NI Act: holiday list must be configurable per state/region.
     *
     * @param date         The date to mark as holiday
     * @param description  Holiday description (mandatory per RBI NI Act)
     * @param holidayType  NATIONAL, STATE, BANK, OPTIONAL, GAZETTED (null defaults to GAZETTED)
     * @param region       Applicable region/state for STATE holidays (null for NATIONAL)
     */
    @Transactional
    public void addHoliday(LocalDate date, String description, String holidayType, String region) {
        String tenantId = TenantContext.getCurrentTenant();
        BusinessCalendar cal = calendarRepository
                .findByTenantIdAndBusinessDate(tenantId, date)
                .orElseThrow(() -> new BusinessException(
                        "DATE_NOT_IN_CALENDAR", "Date " + date + " not in calendar. Generate the month first."));

        if (cal.isDayOpen() || cal.isDayClosed()) {
            throw new BusinessException(
                    "DAY_ALREADY_PROCESSED",
                    "Cannot mark " + date + " as holiday — day is already " + cal.getDayStatus());
        }

        cal.setHoliday(true);
        cal.setHolidayDescription(description);
        cal.setHolidayType(holidayType != null ? holidayType : "GAZETTED");
        cal.setHolidayRegion(region);
        cal.setUpdatedBy(SecurityUtil.getCurrentUsername());
        calendarRepository.save(cal);

        auditService.logEvent(
                "BusinessCalendar",
                cal.getId(),
                "HOLIDAY_ADDED",
                null,
                description,
                "DAY_CONTROL",
                "Holiday added: " + date + " — " + description
                        + " | Type: " + cal.getHolidayType()
                        + (region != null ? " | Region: " + region : "")
                        + " by " + SecurityUtil.getCurrentUsername());
        log.info(
                "Holiday added: date={}, description={}, type={}, region={}",
                date,
                description,
                cal.getHolidayType(),
                region);
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
     * Validates that the previous business day (most recent non-holiday date before
     * the given date) is in DAY_CLOSED status. Skips holidays and weekends.
     *
     * Per Finacle DAYCTRL: days must be processed in strict sequence.
     * The first-ever day open (no previous day exists) is allowed without validation.
     */
    private void validatePreviousDayClosed(String tenantId, LocalDate businessDate) {
        // Walk backwards to find the most recent non-holiday calendar entry
        LocalDate checkDate = businessDate.minusDays(1);
        int maxLookback = 10; // Max days to look back (covers long weekends + holidays)

        for (int i = 0; i < maxLookback; i++) {
            var prevDay = calendarRepository.findByTenantIdAndBusinessDate(tenantId, checkDate);
            if (prevDay.isPresent()) {
                BusinessCalendar prev = prevDay.get();
                if (!prev.isHoliday()) {
                    // Found the previous working day — must be DAY_CLOSED
                    if (!prev.isDayClosed()) {
                        throw new BusinessException(
                                "PREVIOUS_DAY_NOT_CLOSED",
                                "Cannot open " + businessDate + " — previous business day "
                                        + checkDate + " is in " + prev.getDayStatus()
                                        + " status. Close it first (run EOD + Day Close).");
                    }
                    return; // Previous day is closed — validation passed
                }
            }
            checkDate = checkDate.minusDays(1);
        }
        // No previous working day found within lookback window — first day or calendar gap
        // Allow opening (graceful for initial setup / calendar generation)
        log.info(
                "No previous working day found within {} days of {} — allowing day open (initial setup)",
                maxLookback,
                businessDate);
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
     * Remove holiday flag from a calendar date (make it a working day).
     */
    @Transactional
    public void removeHoliday(LocalDate date) {
        String tenantId = TenantContext.getCurrentTenant();
        BusinessCalendar cal = calendarRepository
                .findByTenantIdAndBusinessDate(tenantId, date)
                .orElseThrow(() -> new BusinessException("DATE_NOT_IN_CALENDAR", "Date " + date + " not in calendar."));

        if (cal.isDayOpen() || cal.isDayClosed()) {
            throw new BusinessException(
                    "DAY_ALREADY_PROCESSED", "Cannot modify " + date + " — day is already " + cal.getDayStatus());
        }

        String prevDescription = cal.getHolidayDescription();
        cal.setHoliday(false);
        cal.setHolidayDescription(null);
        cal.setUpdatedBy(SecurityUtil.getCurrentUsername());
        calendarRepository.save(cal);

        auditService.logEvent(
                "BusinessCalendar",
                cal.getId(),
                "HOLIDAY_REMOVED",
                prevDescription,
                null,
                "DAY_CONTROL",
                "Holiday removed: " + date + " (was: " + prevDescription + ") by " + SecurityUtil.getCurrentUsername());
    }
}
