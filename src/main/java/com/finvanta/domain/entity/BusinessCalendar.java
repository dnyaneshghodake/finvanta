package com.finvanta.domain.entity;

import com.finvanta.domain.enums.DayStatus;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Business Calendar per Finacle/Temenos Day Control standards.
 *
 * Day lifecycle:
 *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
 *
 * Rules:
 * - Only ONE day can be in DAY_OPEN status at a time per tenant
 * - All financial transactions must use the business_date of the OPEN day
 * - Day cannot close until EOD completes successfully
 * - Holidays are pre-configured — no transactions allowed on holidays
 * - System date may differ from business date (e.g., EOD runs after midnight)
 *
 * Holiday Types (per RBI Negotiable Instruments Act 1881):
 *   WEEKEND   - Saturday/Sunday (auto-detected during calendar generation)
 *   NATIONAL  - Republic Day, Independence Day, Gandhi Jayanti (all branches)
 *   STATE     - Regional holidays (applicable to specific state/region branches)
 *   BANK      - Bank-specific holidays (declared by bank management)
 *   OPTIONAL  - Optional holidays (staff can choose, bank remains operational)
 *   GAZETTED  - Government gazetted holidays (per state gazette notification)
 *
 * Previous Day Validation:
 *   Before opening a new day, the system validates that the previous business
 *   day is in DAY_CLOSED status. This prevents gaps in the day sequence
 *   (e.g., opening April 5 when April 4 is still DAY_OPEN or NOT_OPENED).
 */
@Entity
@Table(
        name = "business_calendar",
        indexes = {
            @Index(name = "idx_buscal_tenant_date", columnList = "tenant_id, business_date", unique = true),
            @Index(name = "idx_buscal_day_status", columnList = "tenant_id, day_status")
        })
@Getter
@Setter
@NoArgsConstructor
public class BusinessCalendar extends BaseEntity {

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "is_holiday", nullable = false)
    private boolean holiday = false;

    @Column(name = "holiday_description", length = 200)
    private String holidayDescription;

    /**
     * Holiday type classification per RBI Negotiable Instruments Act 1881.
     * Values: WEEKEND, NATIONAL, STATE, BANK, OPTIONAL, GAZETTED
     * Null for non-holiday dates.
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
}
