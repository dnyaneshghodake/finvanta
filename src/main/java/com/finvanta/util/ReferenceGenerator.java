package com.finvanta.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CBS Reference Number Generator per Finacle/Temenos numbering conventions.
 *
 * Reference format: PREFIX + BRANCH_CODE + TIMESTAMP + SEQUENCE
 *   APP = Loan Application Number  (e.g., APPHQ00120260401143022 0001)
 *   LN  = Loan Account Number      (e.g., LNHQ001202604011430220001)
 *   TXN = Transaction Reference     (e.g., TXN202604011430220001)
 *   JRN = Journal Entry Reference   (e.g., JRN202604011430220001)
 *
 * Note: This implementation uses an in-memory AtomicLong sequence which
 * resets on JVM restart. For production CBS deployment, replace with a
 * database sequence (e.g., SQL Server SEQUENCE or Oracle SEQUENCE) to
 * guarantee uniqueness across application restarts and clustered instances.
 */
public final class ReferenceGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ReferenceGenerator() {
    }

    /** Generates loan application number: APP + branchCode + timestamp + seq */
    public static String generateApplicationNumber(String branchCode) {
        return "APP" + branchCode + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    /** Generates loan account number: LN + branchCode + timestamp + seq */
    public static String generateAccountNumber(String branchCode) {
        return "LN" + branchCode + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    /** Generates transaction reference: TXN + timestamp + seq */
    public static String generateTransactionRef() {
        return "TXN" + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    /** Generates journal entry reference: JRN + timestamp + seq */
    public static String generateJournalRef() {
        return "JRN" + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    private static String nextSequence() {
        long val = SEQUENCE.incrementAndGet();
        // Use modulo to keep 4 digits, but avoid 0 to prevent collisions
        long mod = ((val - 1) % 9999) + 1;
        return String.format("%04d", mod);
    }
}
