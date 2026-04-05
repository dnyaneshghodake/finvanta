package com.finvanta.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public final class ReferenceGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ReferenceGenerator() {
    }

    public static String generateApplicationNumber(String branchCode) {
        return "APP" + branchCode + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    public static String generateAccountNumber(String branchCode) {
        return "LN" + branchCode + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    public static String generateTransactionRef() {
        return "TXN" + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    public static String generateJournalRef() {
        return "JRN" + LocalDateTime.now().format(FORMATTER) + nextSequence();
    }

    private static String nextSequence() {
        return String.format("%04d", SEQUENCE.incrementAndGet() % 10000);
    }
}
