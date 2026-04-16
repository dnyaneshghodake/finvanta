package com.finvanta.batch;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.repository.BusinessCalendarRepository;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CBS Calendar Startup Recovery Tests per Finacle DAYCTRL / Temenos COB.
 *
 * Validates that stuck EOD_RUNNING + eodComplete=true entries are
 * corrected to DAY_OPEN + eodComplete=true on application startup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CalendarStartupRecovery — Stuck EOD Detection")
class CalendarStartupRecoveryTest {

    @Mock private BusinessCalendarRepository calendarRepository;
    @Mock private AuditService auditService;

    private CalendarStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new CalendarStartupRecovery(calendarRepository, auditService);
    }

    @Test
    @DisplayName("No stuck entries — no corrections made")
    void noStuckEntries_noCorrections() {
        when(calendarRepository.findStuckEodRunningWithComplete("DEFAULT"))
                .thenReturn(Collections.emptyList());

        recovery.recoverStuckCalendarEntries();

        verify(calendarRepository, never()).save(any());
        verify(auditService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Stuck EOD_RUNNING entry corrected to DAY_OPEN")
    void stuckEntry_correctedToDayOpen() {
        BusinessCalendar stuck = new BusinessCalendar();
        stuck.setId(1L);
        stuck.setTenantId("DEFAULT");
        stuck.setBranchCode("HQ001");
        stuck.setBusinessDate(LocalDate.of(2026, 4, 1));
        stuck.setDayStatus(DayStatus.EOD_RUNNING);
        stuck.setEodComplete(true);
        stuck.setLocked(true);

        when(calendarRepository.findStuckEodRunningWithComplete("DEFAULT"))
                .thenReturn(List.of(stuck));
        when(calendarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recovery.recoverStuckCalendarEntries();

        // Verify state correction
        assertEquals(DayStatus.DAY_OPEN, stuck.getDayStatus());
        assertFalse(stuck.isLocked());
        assertEquals("SYSTEM_RECOVERY", stuck.getUpdatedBy());

        // Verify audit trail
        verify(auditService).logEvent(
                eq("BusinessCalendar"), eq(1L), eq("STARTUP_RECOVERY"),
                any(), any(), eq("DAY_CONTROL"), any());
        verify(calendarRepository).save(stuck);
    }

    @Test
    @DisplayName("Multiple stuck entries all corrected")
    void multipleStuckEntries_allCorrected() {
        BusinessCalendar stuck1 = new BusinessCalendar();
        stuck1.setId(1L);
        stuck1.setTenantId("DEFAULT");
        stuck1.setBranchCode("BR001");
        stuck1.setBusinessDate(LocalDate.of(2026, 4, 1));
        stuck1.setDayStatus(DayStatus.EOD_RUNNING);
        stuck1.setEodComplete(true);
        stuck1.setLocked(true);

        BusinessCalendar stuck2 = new BusinessCalendar();
        stuck2.setId(2L);
        stuck2.setTenantId("DEFAULT");
        stuck2.setBranchCode("BR002");
        stuck2.setBusinessDate(LocalDate.of(2026, 4, 1));
        stuck2.setDayStatus(DayStatus.EOD_RUNNING);
        stuck2.setEodComplete(true);
        stuck2.setLocked(false);

        when(calendarRepository.findStuckEodRunningWithComplete("DEFAULT"))
                .thenReturn(List.of(stuck1, stuck2));
        when(calendarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recovery.recoverStuckCalendarEntries();

        assertEquals(DayStatus.DAY_OPEN, stuck1.getDayStatus());
        assertEquals(DayStatus.DAY_OPEN, stuck2.getDayStatus());
        verify(calendarRepository, times(2)).save(any());
        verify(auditService, times(2)).logEvent(
                eq("BusinessCalendar"), any(), eq("STARTUP_RECOVERY"),
                any(), any(), eq("DAY_CONTROL"), any());
    }
}
