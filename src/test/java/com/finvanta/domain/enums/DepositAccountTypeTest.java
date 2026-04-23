package com.finvanta.domain.enums;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * CBS Test: DepositAccountType enum per Finacle PDDEF ACCT_TYPE.
 *
 * Validates type classification, interest bearing flag, and GL code mapping.
 * Per RBI: incorrect account type classification silently breaks interest
 * calculation, dormancy rules, and CRR/SLR regulatory reporting.
 */
class DepositAccountTypeTest {

    @ParameterizedTest
    @EnumSource(value = DepositAccountType.class, names = {
            "SAVINGS", "SAVINGS_NRI", "SAVINGS_MINOR", "SAVINGS_JOINT", "SAVINGS_PMJDY", "SALARY"})
    @DisplayName("All SAVINGS variants (incl. SALARY) return isSavings()=true")
    void savingsTypes_isSavings_true(DepositAccountType type) {
        assertTrue(type.isSavings());
        assertFalse(type.isCurrent());
    }

    @ParameterizedTest
    @EnumSource(value = DepositAccountType.class, names = {"CURRENT", "CURRENT_OD"})
    @DisplayName("All CURRENT variants return isCurrent()=true")
    void currentTypes_isCurrent_true(DepositAccountType type) {
        assertTrue(type.isCurrent());
        assertFalse(type.isSavings());
    }

    @ParameterizedTest
    @EnumSource(value = DepositAccountType.class, names = {
            "SAVINGS", "SAVINGS_NRI", "SAVINGS_MINOR", "SAVINGS_JOINT", "SAVINGS_PMJDY", "SALARY"})
    @DisplayName("All SAVINGS types (incl. SALARY) are interest-bearing per RBI")
    void savingsTypes_isInterestBearing_true(DepositAccountType type) {
        assertTrue(type.isInterestBearing());
    }

    @ParameterizedTest
    @EnumSource(value = DepositAccountType.class, names = {"CURRENT", "CURRENT_OD"})
    @DisplayName("CURRENT types have zero interest per RBI norms")
    void currentTypes_isInterestBearing_false(DepositAccountType type) {
        assertFalse(type.isInterestBearing());
    }

    @ParameterizedTest
    @EnumSource(value = DepositAccountType.class, names = {
            "SAVINGS", "SAVINGS_NRI", "SAVINGS_MINOR", "SAVINGS_JOINT", "SAVINGS_PMJDY", "SALARY"})
    @DisplayName("Savings accounts (incl. SALARY) map to GL 2010 (SB Deposits)")
    void savingsTypes_glCode_2010(DepositAccountType type) {
        assertEquals("2010", type.getDepositGlCode());
    }

    @ParameterizedTest
    @EnumSource(value = DepositAccountType.class, names = {"CURRENT", "CURRENT_OD"})
    @DisplayName("Current accounts map to GL 2020 (CA Deposits)")
    void currentTypes_glCode_2020(DepositAccountType type) {
        assertEquals("2020", type.getDepositGlCode());
    }

    @Test
    @DisplayName("Invalid account type string throws IllegalArgumentException")
    void valueOf_invalidType_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> DepositAccountType.valueOf("SAVING"));
        assertThrows(IllegalArgumentException.class, () -> DepositAccountType.valueOf("savings"));
        assertThrows(IllegalArgumentException.class, () -> DepositAccountType.valueOf("INVALID"));
    }

    @Test
    @DisplayName("All enum values have non-null display names")
    void allTypes_haveDisplayName() {
        for (DepositAccountType type : DepositAccountType.values()) {
            assertNotNull(type.getDisplayName());
            assertFalse(type.getDisplayName().isBlank());
        }
    }

    @Test
    @DisplayName("Enum has exactly 8 values matching Finacle PDDEF (incl. SALARY)")
    void enumSize_matches_finacle() {
        assertEquals(8, DepositAccountType.values().length);
    }
}
