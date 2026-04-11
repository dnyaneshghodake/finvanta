package com.finvanta.domain.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.DepositAccountType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CBS Test: DepositAccount entity with DepositAccountType enum integration.
 *
 * Validates that the entity's isSavings()/isCurrent() helper methods
 * correctly delegate to the enum and that the accountType field is
 * properly typed (not a raw String).
 */
class DepositAccountTypeIntegrationTest {

    @Test
    @DisplayName("DepositAccount.isSavings() delegates to enum for SAVINGS type")
    void depositAccount_savings_isSavingsTrue() {
        DepositAccount account = new DepositAccount();
        account.setAccountType(DepositAccountType.SAVINGS);

        assertTrue(account.isSavings());
        assertFalse(account.isCurrent());
    }

    @Test
    @DisplayName("DepositAccount.isCurrent() delegates to enum for CURRENT type")
    void depositAccount_current_isCurrentTrue() {
        DepositAccount account = new DepositAccount();
        account.setAccountType(DepositAccountType.CURRENT);

        assertTrue(account.isCurrent());
        assertFalse(account.isSavings());
    }

    @Test
    @DisplayName("DepositAccount.isSavings() returns false for null accountType")
    void depositAccount_nullType_isSavingsFalse() {
        DepositAccount account = new DepositAccount();
        account.setAccountType(null);

        assertFalse(account.isSavings());
        assertFalse(account.isCurrent());
    }

    @Test
    @DisplayName("All SAVINGS variants work with DepositAccount.isSavings()")
    void depositAccount_allSavingsVariants() {
        for (DepositAccountType type : DepositAccountType.values()) {
            DepositAccount account = new DepositAccount();
            account.setAccountType(type);

            if (type.isSavings()) {
                assertTrue(account.isSavings(), "Expected isSavings()=true for " + type);
                assertFalse(account.isCurrent(), "Expected isCurrent()=false for " + type);
            } else {
                assertFalse(account.isSavings(), "Expected isSavings()=false for " + type);
                assertTrue(account.isCurrent(), "Expected isCurrent()=true for " + type);
            }
        }
    }

    @Test
    @DisplayName("DailyBalanceSnapshot accepts DepositAccountType enum")
    void dailyBalanceSnapshot_acceptsEnum() {
        DailyBalanceSnapshot snapshot = new DailyBalanceSnapshot();
        snapshot.setAccountType(DepositAccountType.SAVINGS_PMJDY);

        assertEquals(DepositAccountType.SAVINGS_PMJDY, snapshot.getAccountType());
    }
}
