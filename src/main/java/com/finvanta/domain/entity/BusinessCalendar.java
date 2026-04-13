package com.finvanta.domain.entity;

import com.finvanta.domain.enums.DayStatus;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Business Calendar per Finacle DAYCTRL / Temenos COB Day Control standards.
 *
 * <h3>Tier-1 Branch-Level Day Control:</h3>
 * Per Finacle SOL architecture, day control operates at the BRANCH level.
 * Each branch independently opens, processes, runs EOD, and closes its day.
 * This enables:
 * - Branch A open while Branch B is closed (different time zones)
 * - Maharashtra branches closed for state holiday, Karnataka branches open
 * - Rural branches EOD at 5 PM, metro branches at 8 PM
 * - Independent branch-level EOD retry without affecting other branches
 *
 * Day lifecycle per branch:
 *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
 *
 * Rules:
 * - Only ONE day can be in DAY_OPEN status at a time PER BRANCH
 * - All financial transactions must use the business_date of the branch's OPEN day
 * - Day cannot close until branch EOD completes successfully
 * - Holidays are pre-configured — no transactions allowed on holidays
 * - System date may differ from business date (e.g., EOD runs after midnight)
 *
 * Holiday Types (per RBI Negotiable Instruments Act 1881 / RBI Payment Systems):
 *   WEEKEND   - Saturday/Sunday (auto-detected during calendar generation)
 *   NATIONAL  - Republic Day, Independence Day, Gandhi Jayanti (all branches)
 *   STATE     - Regional holidays (applicable to specific state/region branches)
 *   BANK      - Bank-specific holidays (declared by bank management)
 *   OPTIONAL  - Optional holidays (staff can choose, bank remains operational)
 *   GAZETTED  - Government gazetted holidays (per state gazette notification)
 *   CLEARING  - RBI/NPCI clearing holiday (NEFT/RTGS/IMPS settlement closed,
 *               but branch may be open for non-clearing operations)
 *
 * Previous Day Validation:
 *   Before opening a new day at a branch, the system validates that the
 *   previous business day at THAT BRANCH is in DAY_CLOSED status.
 *
 * HO Consolidation:
 *   After ALL branches complete their EOD, the Head Office runs a
 *   consolidation batch: inter-branch settlement, tenant-level reconciliation,
 *   and consolidated reporting.
 */
@Entity
@Table(
        name = "business_calendar",
        indexes = {
            @Index(
                    name = "idx_buscal_tenant_branch_date",
                    columnList = "tenant_id, branch_id, business_date",
                    unique = true),
            @Index(name = "idx_buscal_day_status", columnList = "tenant_id, branch_id, day_status"),
            @Index(name = "idx_buscal_tenant_date", columnList = "tenant_id, business_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class BusinessCalendar extends BaseEntity {

    /**
     * Branch this calendar entry belongs to.
     * Per Finacle DAYCTRL: day control is per-branch (SOL). Each branch
     * independently opens and closes its day. The unique constraint
     * (tenant_id, branch_id, business_date) ensures one calendar entry
     * per branch per date.
     *
     * For tenant-wide holidays (NATIONAL, WEEKEND), a calendar entry is
     * created for EACH branch. For STATE holidays, entries are created
     * only for branches in the applicable region.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /**
     * Branch code denormalized for efficient queries and display.
     * Avoids joining to branches table for calendar listing pages.
     */
    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "is_holiday", nullable = false)
    private boolean holiday = false;

    @Column(name = "holiday_description", length = 200)
    private String holidayDescription;

    /**
     * Holiday type classification per RBI Negotiable Instruments Act 1881.
     * Values: WEEKEND, NATIONAL, STATE, BANK, OPTIONAL, GAZETTED, CLEARING
     * Null for non-holiday dates.
     *
     * CLEARING: RBI/NPCI-declared clearing holiday — NEFT/RTGS/IMPS settlement
     * systems are closed but the bank branch may be open for non-clearing operations.
     * Per RBI Payment Systems: clearing holidays are independent of bank holidays.
     * Example: RBI may declare a clearing holiday on a day when banks are open.
     */
    @Column(name = "holiday_type", length = 20)
    private String holidayType;

    /**
     * Applicable region/state for STATE holidays.
     * Null for NATIONAL/WEEKEND holidays (apply to all branches).
     * Per RBI NI Act: state holidays vary by region — a holiday in Maharashtra
     * may not be a holiday in Karnataka.
     */
    @Column(name = "holiday_region", length = 100)
    private String holidayRegion;

    /**
     * Day status per CBS lifecycle:
     *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_status", nullable = false, length = 20)
    private DayStatus dayStatus = DayStatus.NOT_OPENED;

    @Column(name = "is_eod_complete", nullable = false)
    private boolean eodComplete = false;

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    /** Who opened the day */
    @Column(name = "day_opened_by", length = 100)
    private String dayOpenedBy;

    /** When the day was opened */
    @Column(name = "day_opened_at")
    private LocalDateTime dayOpenedAt;

    /** Who closed the day */
    @Column(name = "day_closed_by", length = 100)
    private String dayClosedBy;

    /** When the day was closed */
    @Column(name = "day_closed_at")
    private LocalDateTime dayClosedAt;

    public boolean isDayOpen() {
        return dayStatus == DayStatus.DAY_OPEN;
    }

    public boolean isDayClosed() {
        return dayStatus == DayStatus.DAY_CLOSED;
    }

    public boolean isNotOpened() {
        return dayStatus == DayStatus.NOT_OPENED;
    }

    public boolean isEodRunning() {
        return dayStatus == DayStatus.EOD_RUNNING;
    }

    /**
     * Returns true if this is a clearing-specific holiday.
     * Per RBI Payment Systems: clearing holidays (NEFT/RTGS/IMPS settlement closed)
     * are independent of bank holidays. A branch may be open for non-clearing
     * operations (CASA deposits, withdrawals, loan repayments) on a clearing holiday.
     *
     * Used by ClearingEngine to block outward clearing initiation on clearing holidays
     * while allowing other financial operations to proceed normally.
     */
    public boolean isClearingHoliday() {
        return holiday && "CLEARING".equals(holidayType);
    }
}
