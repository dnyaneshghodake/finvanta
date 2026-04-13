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
 *   {SOL:3}{SERIAL:7}{CHECK:1}  → 00200000017          (11 digits) — Customer CIF
 *   LN-{BRANCH}-{6-digit}      → LN-BR001-000045      (16 chars) — Loan Account
 *   APP-{BRANCH}-{6-digit}     → APP-BR001-000045     (17 chars) — Loan Application
 *   SB-{BRANCH}-{6-digit}      → SB-BR001-000001      (16 chars) — Savings Account
 *   CA-{BRANCH}-{6-digit}      → CA-BR001-000001      (16 chars) — Current Account
 *   TXN-{YYYYMMDD}-{6-digit}   → TXN-20260412-000789  (20 chars) — Transaction Ref
 *   JRN-{YYYYMMDD}-{6-digit}   → JRN-20260412-000789  (20 chars) — Journal Ref
 *   COL-{6-digit}              → COL-000056            (10 chars) — Collateral Ref
 *
 * CBS ARCHITECTURE — Two-tier sequence strategy:
 *
 * PERSISTENT references (CIF, Account Numbers, Loan Numbers, Collateral Refs):
 *   These are printed on passbooks, reported to CIBIL/CRILC, and must be globally
 *   unique across JVM restarts and cluster nodes. They MUST use DB-backed sequences
 *   via {@link com.finvanta.service.SequenceGeneratorService}. The static methods
 *   in this class for CIF/Account generation are DEPRECATED — callers must use
 *   {@link com.finvanta.service.CbsReferenceService} instead.
 *
 * EPHEMERAL references (TXN refs, JRN refs):
 *   These are generated within a single @Transactional boundary and are immediately
 *   persisted with a DB unique constraint as safety net. In-memory AtomicLong is
 *   acceptable here because duplicates are caught by the constraint and the entire
 *   transaction retries. These do NOT need to be sequential across restarts.
 *
 * KNOWN LIMITATION — fmt() 6-digit overflow:
 *   String.format("%06d") is minimum width, not maximum. The AtomicLong seed starts
 *   at System.nanoTime() % 100000 (~5 digits). After ~900K increments within a single
 *   JVM lifecycle, the value exceeds 999999 and produces 7+ digit serials. This changes
 *   reference length (e.g., TXN-20260412-1000000 = 21 chars instead of 20). All reference
 *   columns are VARCHAR(40) so no data truncation occurs, but the fixed-width format
 *   contract is broken. For ephemeral refs (TXN/JRN) this is cosmetic — they are not
 *   parsed by external systems. For persistent refs, use CbsReferenceService (DB-backed).
 */
public final class ReferenceGenerator {

    // CBS: In-memory sequences for EPHEMERAL references only (TXN, JRN).
    // These are generated within a @Transactional and protected by DB unique constraints.
    // Seeded from System.nanoTime() to reduce (not eliminate) restart collision probability.
    // For persistent references (CIF, Account), use CbsReferenceService (DB-backed).
    private static final AtomicLong TXN_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    private static final AtomicLong JRN_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));

    // DEPRECATED: In-memory sequences for persistent references.
    // These produce non-sequential, non-deterministic numbers that start at random
    // offsets on each JVM restart. NOT suitable for CIF/Account numbers that are
    // printed on passbooks, reported to CIBIL, and must be sequential.
    // Retained ONLY for backward compatibility — callers should migrate to
    // CbsReferenceService which uses DB-backed SequenceGeneratorService.
    @Deprecated(forRemoval = true)
    private static final AtomicLong CUST_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    @Deprecated(forRemoval = true)
    private static final AtomicLong LOAN_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    @Deprecated(forRemoval = true)
    private static final AtomicLong APP_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    @Deprecated(forRemoval = true)
    private static final AtomicLong CASA_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));
    @Deprecated(forRemoval = true)
    private static final AtomicLong COL_SEQ = new AtomicLong(Math.abs(System.nanoTime() % 100000));

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ReferenceGenerator() {}

    /**
     * Customer CIF Number per Finacle CIF_MASTER — 11 pure digits.
     *
     * Structure: {SOL_ID:3}{SERIAL:7}{CHECK:1} = 11 digits
     *   SOL_ID  = 3-digit branch identifier (from DB branch ID, mod 1000)
     *   SERIAL  = 7-digit sequential within the branch
     *   CHECK   = 1-digit Luhn check digit (catches typos + transpositions)
     *
     * Example: Branch ID=2 → SOL=002, serial=0000001, check=7 → "00200000017"
     *
     * Per Finacle/SBI: CIF is 11-digit pure numeric.
     * Per Temenos: CUSTOMER ID is 6-10 digit numeric.
     * The SOL prefix enables branch identification without DB lookup.
     * The Luhn check digit catches ~98% of single-digit and transposition errors.
     * Printed on passbooks, cheque books, CIBIL reports, KYC documents.
     *
     * @param branchId Database branch ID (used as SOL prefix, mod 1000)
     */
    public static String generateCustomerNumber(Long branchId) {
        String sol = String.format("%03d", branchId != null ? branchId % 1000 : 0);
        String serial = String.format("%07d", CUST_SEQ.incrementAndGet() % 10000000);
        String base = sol + serial; // 10 digits
        int checkDigit = computeLuhn(base);
        return base + checkDigit; // 11 digits
    }

    /**
     * @deprecated Use {@link #generateCustomerNumber(Long)} with branch ID.
     */
    @Deprecated(forRemoval = true)
    public static String generateCustomerNumber(String branchCode) {
        return generateCustomerNumber(0L);
    }

    /**
     * Luhn algorithm (Modulus 10) check digit per ISO/IEC 7812-1.
     * Used by Finacle CIF, credit card numbers, IMEI, and ISIN.
     * Catches: 100% of single-digit errors, ~98% of transposition errors.
     *
     * Per ISO/IEC 7812-1: starting from the rightmost digit and moving left,
     * double every SECOND digit (i.e., the rightmost digit is NOT doubled,
     * the second-from-right IS doubled, third is not, fourth is doubled, etc.).
     */
    public static int computeLuhn(String digits) {
        int sum = 0;
        boolean doubleNext = false; // Rightmost digit is NOT doubled
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (doubleNext) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            doubleNext = !doubleNext;
        }
        return (10 - (sum % 10)) % 10;
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
        return "JRN-" + today() + "-" + fmt(JRN_SEQ);
    }

    /** Collateral Ref: COL-{6-digit} → COL-000056 */
    public static String generateCollateralRef() {
        return "COL-" + fmt(COL_SEQ);
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
