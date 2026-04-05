package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private final AuditService auditService;

    public BusinessDateService(BusinessCalendarRepository calendarRepository,
                                AuditService auditService) {
        this.calendarRepository = calendarRepository;
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
        return calendarRepository.findOpenDay(tenantId)
            .map(BusinessCalendar::getBusinessDate)
            .orElseThrow(() -> new BusinessException("NO_OPEN_DAY",
                "No business day is currently open. Contact administrator to open the day."));
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
     * Opens a business day. Only one day can be open at a time.
     * Previous day must be in DAY_CLOSED status.
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
            throw new BusinessException("DAY_ALREADY_OPEN",
                "Business date " + openDay.getBusinessDate() + " is already open. Close it before opening a new day.");
        });

        BusinessCalendar calendar = calendarRepository
            .findAndLockByTenantIdAndDate(tenantId, businessDate)
            .orElseThrow(() -> new BusinessException("DATE_NOT_IN_CALENDAR",
                "Business date " + businessDate + " not found in calendar. Add it first."));

        if (calendar.isDayOpen()) {
            throw new BusinessException("DAY_ALREADY_OPEN",
                "Business date " + businessDate + " is already open.");
        }

        if (calendar.isDayClosed()) {
            throw new BusinessException("DAY_ALREADY_CLOSED",
                "Business date " + businessDate + " is already closed. Cannot reopen.");
        }

        if (calendar.isHoliday()) {
            throw new BusinessException("DAY_IS_HOLIDAY",
                "Cannot open a holiday: " + businessDate);
        }

        calendar.setDayStatus(DayStatus.DAY_OPEN);
        calendar.setDayOpenedBy(currentUser);
        calendar.setDayOpenedAt(LocalDateTime.now());
        calendar.setUpdatedBy(currentUser);

        BusinessCalendar saved = calendarRepository.save(calendar);

        auditService.logEvent("BusinessCalendar", saved.getId(), "DAY_OPEN",
            "NOT_OPENED", "DAY_OPEN", "DAY_CONTROL",
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
            .orElseThrow(() -> new BusinessException("DATE_NOT_IN_CALENDAR",
                "Business date " + businessDate + " not found in calendar."));

        if (!calendar.getDayStatus().canClose()) {
            throw new BusinessException("DAY_NOT_OPEN",
                "Business date " + businessDate + " is not in a closeable state. Current: " + calendar.getDayStatus());
        }

        if (!calendar.isEodComplete()) {
            throw new BusinessException("EOD_NOT_COMPLETE",
                "Cannot close day " + businessDate + " — EOD has not completed successfully.");
        }

        calendar.setDayStatus(DayStatus.DAY_CLOSED);
        calendar.setDayClosedBy(currentUser);
        calendar.setDayClosedAt(LocalDateTime.now());
        calendar.setLocked(false);
        calendar.setUpdatedBy(currentUser);

        BusinessCalendar saved = calendarRepository.save(calendar);

        auditService.logEvent("BusinessCalendar", saved.getId(), "DAY_CLOSE",
            "DAY_OPEN", "DAY_CLOSED", "DAY_CONTROL",
            "Business day closed: " + businessDate + " by " + currentUser);

        log.info("Business day closed: date={}, user={}", businessDate, currentUser);

        return saved;
    }
}