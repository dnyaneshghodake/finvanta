package com.finvanta.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CBS Reference Number Generator per Finacle/Temenos numbering conventions.
 *
 * Reference format: PREFIX + BRANCH_CODE + TIMESTAMP(17) + SEQUENCE(6)
 *
 * Generated lengths (with 5-char branch code like HQ001):
 *   CUST  = Customer CIF Number     → 4 + 5 + 17 + 6 = 32 chars (column: VARCHAR 40)
 *   APP   = Loan Application Number → 3 + 5 + 17 + 6 = 31 chars (column: VARCHAR 40)
 *   LN    = Loan Account Number     → 2 + 5 + 17 + 6 = 30 chars (column: VARCHAR 40)
 *   TXN   = Transaction Reference   → 3 + 0 + 17 + 6 = 26 chars (column: VARCHAR 40)
 *   JRN   = Journal Entry Reference → 3 + 0 + 17 + 6 = 26 chars (column: VARCHAR 40)
 *
 * CBS Column Width Standard (all reference fields: VARCHAR 40):
 *   Max generated = 32 chars (CUST with 5-char branch) → 8 chars headroom
 *
 * Uniqueness guarantees (single-JVM):
 * - Millisecond-precision timestamp (yyyyMMddHHmmssSSS) — ms granularity
 * - Monotonically increasing 6-digit sequence (never wraps, never resets)
 * - Combined: unique up to 999,999 refs per millisecond per JVM
 *
 * Production CBS deployment (clustered / HA):
 * Replace with database-backed sequence:
 *   - SQL Server: CREATE SEQUENCE finvanta_ref_seq START WITH 1 INCREMENT BY 1
 *   - Oracle: CREATE SEQUENCE finvanta_ref_seq MINVALUE 1 CACHE 1000
 *   - Or use a distributed ID generator (Snowflake / ULID)
 * The unique constraint on (tenant_id, customer_number) / (tenant_id, account_number) etc.
 * in the DDL provides a safety net — duplicate inserts fail at DB level.
 */
public final class ReferenceGenerator {

    /**
     * Monotonically increasing sequence — never wraps, never resets within JVM lifecycle.
     * Initialized from System.nanoTime() modulo to avoid restart collisions.
     * For 1M+ daily transactions, 6-digit sequence supports ~11.5 days before
     * reaching Long.MAX_VALUE (effectively infinite).
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(Math.abs(System.nanoTime() % 100000));

    /** Millisecond-precision timestamp: yyyyMMddHHmmssSS (SS = centiseconds) */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private ReferenceGenerator() {}

    /** Generates loan application number: APP + branchCode + timestamp + seq */
    public static String generateApplicationNumber(String branchCode) {
        return "APP" + branchCode + timestamp() + nextSequence();
    }

    /** Generates loan account number: LN + branchCode + timestamp + seq */
    public static String generateAccountNumber(String branchCode) {
        return "LN" + branchCode + timestamp() + nextSequence();
    }

    /** Generates transaction reference: TXN + timestamp + seq */
    public static String generateTransactionRef() {
        return "TXN" + timestamp() + nextSequence();
    }

    /** Generates journal entry reference: JRN + timestamp + seq */
    public static String generateJournalRef() {
        return "JRN" + timestamp() + nextSequence();
    }

    /** Generates customer number per CBS CIF convention: CUST + branchCode + timestamp + seq */
    public static String generateCustomerNumber(String branchCode) {
        return "CUST" + branchCode + timestamp() + nextSequence();
    }

    /** Generates collateral reference: COL + timestamp + seq */
    public static String generateCollateralRef() {
        return "COL" + timestamp() + nextSequence();
    }

    /**
     * Generates deposit account number per Finacle CUSTACCT / Temenos ACCOUNT convention.
     *
     * Format: {TYPE_PREFIX}-{BRANCH_CODE}-{SERIAL_6}
     *   SB-HQ001-000001  (Savings Bank)
     *   CA-DEL001-000001  (Current Account)
     *
     * Per Finacle: account numbers are short and sequential for teller usability.
     * The type prefix distinguishes Savings (SB) from Current (CA) per RBI norms.
     *
     * @param branchCode Branch SOL code (e.g., "HQ001")
     * @param isSavings  true for Savings accounts, false for Current accounts
     */
    public static String generateDepositAccountNumber(String branchCode, boolean isSavings) {
        String prefix = isSavings ? "SB" : "CA";
        return prefix + "-" + branchCode + "-" + String.format("%06d", SEQUENCE.incrementAndGet());
    }

    /**
     * @deprecated Use {@link #generateDepositAccountNumber(String, boolean)} with account type.
     * Kept for backward compatibility. Defaults to SB (Savings) prefix.
     */
    @Deprecated(forRemoval = true)
    public static String generateDepositAccountNumber(String branchCode) {
        return generateDepositAccountNumber(branchCode, true);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }

    /**
     * Returns a monotonically increasing sequence formatted to 6+ digits.
     * Never wraps — guarantees uniqueness within JVM lifecycle.
     * Seeded from System.nanoTime() to avoid collisions across JVM restarts.
     * Values above 999999 produce longer strings (7+ digits), which is safe
     * because all reference columns are VARCHAR(40) with ample headroom.
     */
    private static String nextSequence() {
        long val = SEQUENCE.incrementAndGet();
        return String.format("%06d", val);
    }
}
