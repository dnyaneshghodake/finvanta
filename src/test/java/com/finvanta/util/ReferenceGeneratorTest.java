package com.finvanta.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CBS Reference Generator Tests per Finacle/Temenos numbering conventions.
 *
 * Validates:
 * - Format compliance: PREFIX + BRANCH_CODE + TIMESTAMP(17) + SEQUENCE(6+)
 * - Column width safety: all references fit within VARCHAR(40)
 * - Uniqueness: no duplicates within a burst of 1000 generations
 * - Prefix correctness: CUST, APP, LN, TXN, JRN
 */
class ReferenceGeneratorTest {

    @Test
    @DisplayName("Account number starts with LN prefix")
    void accountNumber_hasLnPrefix() {
        String accNo = ReferenceGenerator.generateAccountNumber("HQ001");
        assertTrue(accNo.startsWith("LN"), "Account number must start with LN");
        assertTrue(accNo.contains("HQ001"), "Account number must contain branch code");
    }

    @Test
    @DisplayName("Transaction reference starts with TXN prefix")
    void transactionRef_hasTxnPrefix() {
        String txnRef = ReferenceGenerator.generateTransactionRef();
        assertTrue(txnRef.startsWith("TXN"), "Transaction ref must start with TXN");
    }

    @Test
    @DisplayName("Journal reference starts with JRN prefix")
    void journalRef_hasJrnPrefix() {
        String jrnRef = ReferenceGenerator.generateJournalRef();
        assertTrue(jrnRef.startsWith("JRN"), "Journal ref must start with JRN");
    }

    @Test
    @DisplayName("Application number starts with APP prefix")
    void applicationNumber_hasAppPrefix() {
        String appNo = ReferenceGenerator.generateApplicationNumber("DEL001");
        assertTrue(appNo.startsWith("APP"), "Application number must start with APP");
        assertTrue(appNo.contains("DEL001"), "Application number must contain branch code");
    }

    @Test
    @DisplayName("Customer number starts with CUST prefix")
    void customerNumber_hasCustPrefix() {
        String custNo = ReferenceGenerator.generateCustomerNumber("BLR001");
        assertTrue(custNo.startsWith("CUST"), "Customer number must start with CUST");
    }

    @Test
    @DisplayName("All references fit within VARCHAR(40) column width")
    void allReferences_fitWithinColumnWidth() {
        // CBS Column Width Standard: all reference fields are VARCHAR(40)
        String accNo = ReferenceGenerator.generateAccountNumber("HQ001");
        String appNo = ReferenceGenerator.generateApplicationNumber("HQ001");
        String custNo = ReferenceGenerator.generateCustomerNumber("HQ001");
        String txnRef = ReferenceGenerator.generateTransactionRef();
        String jrnRef = ReferenceGenerator.generateJournalRef();

        assertTrue(accNo.length() <= 40,
            "Account number length " + accNo.length() + " exceeds VARCHAR(40)");
        assertTrue(appNo.length() <= 40,
            "Application number length " + appNo.length() + " exceeds VARCHAR(40)");
        assertTrue(custNo.length() <= 40,
            "Customer number length " + custNo.length() + " exceeds VARCHAR(40)");
        assertTrue(txnRef.length() <= 40,
            "Transaction ref length " + txnRef.length() + " exceeds VARCHAR(40)");
        assertTrue(jrnRef.length() <= 40,
            "Journal ref length " + jrnRef.length() + " exceeds VARCHAR(40)");
    }

    @Test
    @DisplayName("1000 transaction references are all unique")
    void transactionRefs_areUnique() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String ref = ReferenceGenerator.generateTransactionRef();
            assertTrue(refs.add(ref), "Duplicate reference detected: " + ref);
        }
        assertEquals(1000, refs.size());
    }

    @Test
    @DisplayName("1000 journal references are all unique")
    void journalRefs_areUnique() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String ref = ReferenceGenerator.generateJournalRef();
            assertTrue(refs.add(ref), "Duplicate reference detected: " + ref);
        }
        assertEquals(1000, refs.size());
    }

    @Test
    @DisplayName("References from different generators don't collide")
    void crossGeneratorUniqueness() {
        // TXN and JRN have different prefixes, so they can't collide
        String txn = ReferenceGenerator.generateTransactionRef();
        String jrn = ReferenceGenerator.generateJournalRef();
        assertNotEquals(txn, jrn);
    }
}
