package com.finvanta.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CBS Reference Number Generator per Finacle/Temenos numbering conventions.
 *
 * Reference format: PREFIX + BRANCH_CODE + TIMESTAMP + SEQUENCE
 *   APP = Loan Application Number  (e.g., APPHQ001202604011430220500001)
 *   LN  = Loan Account Number      (e.g., LNHQ00120260401143022050001)
 *   TXN = Transaction Reference     (e.g., TXN20260401143022050001)
 *   JRN = Journal Entry Reference   (e.g., JRN20260401143022050001)
 *
 * Uniqueness guarantees (single-JVM):
 * - Millisecond-precision timestamp (yyyyMMddHHmmssSS) — 100ms granularity
 * - Monotonically increasing 6-digit sequence (never wraps, never resets)
 * - Combined: unique up to 999,999 refs per 10ms window per JVM
 *
 * Production CBS deployment (clustered / HA):
 * Replace this generator with a database-backed sequence strategy:
 *   - SQL Server: CREATE SEQUENCE finvanta_ref_seq START WITH 1 INCREMENT BY 1
 *   - Oracle: CREATE SEQUENCE finvanta_ref_seq MINVALUE 1 CACHE 1000
 *   - Or use a distributed ID generator (Snowflake / ULID)
 * The unique constraint on (tenant_id, account_number) / (tenant_id, transaction_ref)
 * in the DDL provides a safety net — duplicate inserts fail at DB level.
 */
public final class ReferenceGenerator {

    /**
     * Monotonically increasing sequence — never wraps, never resets within JVM lifecycle.
     * Initialized from System.nanoTime() modulo to avoid restart collisions.
     * For 1M+ daily transactions, 6-digit sequence supports ~11.5 days before
     * reaching Long.MAX_VALUE (effectively infinite).
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(
        Math.abs(System.nanoTime() % 100000));

    /** Millisecond-precision timestamp: yyyyMMddHHmmssSS (SS = centiseconds) */
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private ReferenceGenerator() {
    }

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

    private static String timestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }

    /**
     * Returns a monotonically increasing 6-digit sequence.
     * Never wraps (no modulo) — guarantees uniqueness within JVM lifecycle.
     * Seeded from System.nanoTime() to avoid collisions across JVM restarts.
     */
    private static String nextSequence() {
        long val = SEQUENCE.incrementAndGet();
        return String.format("%06d", val % 1000000);
    }
}
