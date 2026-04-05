package com.finvanta.domain.entity;

import com.finvanta.domain.enums.DayStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
 */
@Entity
@Table(name = "business_calendar", indexes = {
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
