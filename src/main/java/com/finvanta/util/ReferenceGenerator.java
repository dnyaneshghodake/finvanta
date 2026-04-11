package com.finvanta.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CBS Reference Number Generator per Finacle/Temenos Tier-1 numbering conventions.
 *
 * Per Finacle CIF / Temenos CUSTOMER / SBI / HDFC / ICICI standards:
 * All reference numbers must be SHORT, SEQUENTIAL, and HUMAN-READABLE.
 * Tellers type them at counters, customers quote them over phone,
 * they're printed on passbooks/cheques, and reported to CIBIL/CRILC.
 *
 * Formats:
 *   CUST-{6-digit}              → CUST-000123          (11 chars) — Customer CIF
 *   LN-{BRANCH}-{6-digit}      → LN-BR001-000045      (16 chars) — Loan Account
 *   APP-{BRANCH}-{6-digit}     → APP-BR001-000045     (17 chars) — Loan Application
 *   SB-{BRANCH}-{6-digit}      → SB-BR001-000001      (16 chars) — Savings Account
 *   CA-{BRANCH}-{6-digit}      → CA-BR001-000001      (16 chars) — Current Account
 *   TXN-{YYYYMMDD}-{6-digit}   → TXN-20260412-000789  (20 chars) — Transaction Ref
 *   JRN-{YYYYMMDD}-{6-digit}   → JRN-20260412-000789  (20 chars) — Journal Ref
 *   COL-{6-digit}              → COL-000056            (10 chars) — Collateral Ref
 *
 * Each entity type has its own independent AtomicLong sequence to prevent
 * cross-type gaps. All seeded from System.nanoTime() to avoid restart collisions.
 * Production: replace with DB-backed sequences (CREATE SEQUENCE).
 */
public final class ReferenceGenerator {

    // Per Finacle: each entity type has its own independent sequence.
    // All seeded from System.nanoTime() modulo to avoid restart collisions.
    // Production: replace with DB-backed sequences (CREATE SEQUENCE).
    private static final AtomicLong CUST_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    private static final AtomicLong LOAN_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    private static final AtomicLong APP_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    private static final AtomicLong CASA_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    private static final AtomicLong TXN_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ReferenceGenerator() {}

    /**
     * Customer CIF Number per Finacle CIF_MASTER (11 chars).
     * Format: CIF{8-digit-seq} → CIF00012345 (11 chars)
     * Per Finacle/SBI: CIF is 11-digit. We use 3-char prefix + 8-digit seq
     * to match the standard length while keeping type-identifiability.
     * Printed on passbooks, cheque books, CIBIL reports, KYC documents.
     */
    public static String generateCustomerNumber(String branchCode) {
        return "CIF" + String.format("%08d", CUST_SEQ.incrementAndGet());
    }

    /** Loan Account: LN-{BRANCH}-{6-digit} → LN-BR001-000045 */
    public static String generateAccountNumber(String branchCode) {
        return "LN-" + branchCode + "-" + fmt(LOAN_SEQ);
    }

    /** Loan Application: APP-{BRANCH}-{6-digit} → APP-BR001-000045 */
    public static String generateApplicationNumber(String branchCode) {
        return "APP-" + branchCode + "-" + fmt(APP_SEQ);
    }

    /** Transaction Ref: TXN-{YYYYMMDD}-{6-digit} → TXN-20260412-000789 */
    public static String generateTransactionRef() {
        return "TXN-" + today() + "-" + fmt(TXN_SEQ);
    }

    /** Journal Ref: JRN-{YYYYMMDD}-{6-digit} → JRN-20260412-000789 */
    public static String generateJournalRef() {
        return "JRN-" + today() + "-" + fmt(TXN_SEQ);
    }

    /** Collateral Ref: COL-{6-digit} → COL-000056 */
    public static String generateCollateralRef() {
        return "COL-" + fmt(TXN_SEQ);
    }

    /** CASA Account: {SB|CA}-{BRANCH}-{6-digit} → SB-BR001-000001 */
    public static String generateDepositAccountNumber(String branchCode, boolean isSavings) {
        return (isSavings ? "SB" : "CA") + "-" + branchCode + "-" + fmt(CASA_SEQ);
    }

    /** @deprecated Use {@link #generateDepositAccountNumber(String, boolean)} */
    @Deprecated(forRemoval = true)
    public static String generateDepositAccountNumber(String branchCode) {
        return generateDepositAccountNumber(branchCode, true);
    }

    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    private static String fmt(AtomicLong seq) {
        return String.format("%06d", seq.incrementAndGet());
    }
}
