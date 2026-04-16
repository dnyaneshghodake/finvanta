package com.finvanta.batch;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.util.TenantContext;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Calendar Startup Recovery per Finacle DAYCTRL / Temenos COB.
 *
 * Detects and corrects calendar entries stuck in an inconsistent state
 * (EOD_RUNNING + eodComplete=true) from prior application runs. This state
 * occurs when:
 *   1. The JVM crashed/restarted between EOD completion and finalizeEod()
 *   2. A prior code bug set eodComplete=true without transitioning dayStatus
 *   3. Database was restored from a backup taken during EOD
 *
 * Per Finacle DAYCTRL / Temenos COB startup recovery:
 *   - Scan for inconsistent calendar states on every application startup
 *   - Correct EOD_RUNNING + eodComplete=true → DAY_OPEN + eodComplete=true
 *   - Log every correction to the audit trail per RBI IT Governance §8.3
 *   - This is a standard CBS pattern — Finacle runs DAYCTRL_RECOVERY at startup
 *
 * Per RBI IT Governance Direction 2023 Section 7.3:
 *   - System recovery must be automated where possible
 *   - All recovery actions must be audited
 *   - Manual SQL updates on financial tables are prohibited
 */
@Component
public class CalendarStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(CalendarStartupRecovery.class);

    private final BusinessCalendarRepository calendarRepository;
    private final AuditService auditService;

    public CalendarStartupRecovery(
            BusinessCalendarRepository calendarRepository,
            AuditService auditService) {
        this.calendarRepository = calendarRepository;
        this.auditService = auditService;
    }

    /**
     * Runs on application startup after all beans are initialized.
     * Detects and corrects stuck EOD_RUNNING calendar entries.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverStuckCalendarEntries() {
        String tenantId = "DEFAULT"; // CBS: Multi-tenant systems would iterate all tenants
        TenantContext.setCurrentTenant(tenantId);

        try {
            List<BusinessCalendar> stuckEntries =
                    calendarRepository.findStuckEodRunningWithComplete(tenantId);

            if (stuckEntries.isEmpty()) {
                log.debug("Calendar startup recovery: no stuck entries found");
                return;
            }

            log.warn("CBS STARTUP RECOVERY: Found {} calendar entries stuck in "
                    + "EOD_RUNNING with eodComplete=true — correcting to DAY_OPEN",
                    stuckEntries.size());

            for (BusinessCalendar cal : stuckEntries) {
                String previousState = "EOD_RUNNING+eodComplete=true+locked=" + cal.isLocked();

                cal.setDayStatus(DayStatus.DAY_OPEN);
                cal.setLocked(false);
                cal.setUpdatedBy("SYSTEM_RECOVERY");
                calendarRepository.save(cal);

                auditService.logEvent(
                        "BusinessCalendar",
                        cal.getId(),
                        "STARTUP_RECOVERY",
                        previousState,
                        "DAY_OPEN+eodComplete=true+locked=false",
                        "DAY_CONTROL",
                        "Startup recovery: corrected stuck EOD_RUNNING state for "
                                + cal.getBusinessDate() + " at branch " + cal.getBranchCode()
                                + ". Previous: " + previousState
                                + ". Per Finacle DAYCTRL_RECOVERY: auto-corrected on application startup.");

                log.info("RECOVERED: branch={}, date={}, {} → DAY_OPEN+eodComplete=true",
                        cal.getBranchCode(), cal.getBusinessDate(), previousState);
            }

            log.info("CBS STARTUP RECOVERY: corrected {} stuck calendar entries", stuckEntries.size());
        } finally {
            TenantContext.clear();
        }
    }
}
