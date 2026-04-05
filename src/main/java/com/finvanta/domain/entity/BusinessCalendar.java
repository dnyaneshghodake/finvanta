package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "business_calendar", indexes = {
    @Index(name = "idx_buscal_tenant_date", columnList = "tenant_id, business_date", unique = true)
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

    @Column(name = "is_eod_complete", nullable = false)
    private boolean eodComplete = false;

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;
}
