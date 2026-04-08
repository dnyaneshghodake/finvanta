package com.finvanta.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InterestAccrual entity lifecycle.
 */
@DisplayName("InterestAccrual Entity Tests")
public class InterestAccrualEntityTest {

    @Test
    @DisplayName("Create interest accrual with correct lifecycle")
    void testCreateInterestAccrual() {
        // Arrange
        InterestAccrual accrual = new InterestAccrual();
        accrual.setTenantId("DEFAULT");
        accrual.setAccountId(1001L);
        accrual.setAccrualDate(LocalDate.of(2026, 4, 7));
        accrual.setPrincipalBase(new BigDecimal("100000.00"));
        accrual.setRateApplied(new BigDecimal("12.0000"));
        accrual.setDaysCount(1);
        accrual.setAccruedAmount(new BigDecimal("32.88"));
        accrual.setAccrualType("REGULAR");
        accrual.setPostedFlag(false);

        // Assert - pre-posting state
        assertEquals("DEFAULT", accrual.getTenantId());
        assertEquals(1001L, accrual.getAccountId());
        assertEquals(LocalDate.of(2026, 4, 7), accrual.getAccrualDate());
        assertEquals("REGULAR", accrual.getAccrualType());
        assertFalse(accrual.getPostedFlag());
        assertNull(accrual.getJournalEntryId());
        assertNull(accrual.getTransactionRef());

        // Act - simulate GL posting
        accrual.setPostedFlag(true);
        accrual.setPostingDate(LocalDate.of(2026, 4, 7));
        accrual.setJournalEntryId(1001L);
        accrual.setTransactionRef("TXN202604070001");

        // Assert - post-posting state
        assertTrue(accrual.getPostedFlag());
        assertNotNull(accrual.getPostingDate());
        assertNotNull(accrual.getJournalEntryId());
        assertNotNull(accrual.getTransactionRef());
    }

    @Test
    @DisplayName("Create penal interest accrual")
    void testCreatePenalAccrual() {
        // Arrange
        InterestAccrual penal = new InterestAccrual();
        penal.setTenantId("DEFAULT");
        penal.setAccountId(1001L);
        penal.setAccrualDate(LocalDate.of(2026, 4, 7));
        penal.setPrincipalBase(new BigDecimal("20000.00")); // Overdue amount
        penal.setRateApplied(new BigDecimal("2.0000")); // Penal rate
        penal.setDaysCount(1);
        penal.setAccruedAmount(new BigDecimal("1.10"));
        penal.setAccrualType("PENAL");
        penal.setPostedFlag(true);

        // Assert
        assertEquals("PENAL", penal.getAccrualType());
        assertEquals(new BigDecimal("2.0000"), penal.getRateApplied());
    }
}
