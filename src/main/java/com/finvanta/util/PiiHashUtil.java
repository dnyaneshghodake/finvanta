package com.finvanta.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * CBS PII Hash Utility — Single source of truth for PII de-duplication hashing.
 *
 * Per RBI KYC Master Direction 2016: one PAN = one CIF.
 * Since PAN/Aadhaar are encrypted (AES-256-GCM with random IV), the same plaintext
 * produces different ciphertext each time. DB-level uniqueness checks on ciphertext
 * are impossible. SHA-256 hash enables duplicate detection without decryption:
 *   hash(PAN1) == hash(PAN2) → same PAN → duplicate CIF.
 *
 * This utility is the SINGLE implementation shared by:
 *   - Customer entity (computePanHash, computeAadhaarHash)
 *   - CustomerCifServiceImpl (validateCustomerFields, searchCustomers PAN lookup)
 *
 * Per Finacle CIF_MASTER / Temenos CUSTOMER: PII hash computation must be
 * deterministic, normalized (uppercase + trim), and use SHA-256 per NIST FIPS 180-4.
 */
public final class PiiHashUtil {

    private PiiHashUtil() {}

    /**
     * Computes SHA-256 hash of a PII value for de-duplication.
     * Normalizes input to uppercase + trim before hashing to ensure deterministic results.
     *
     * @param input PII value (PAN, Aadhaar, etc.)
     * @return 64-character lowercase hex SHA-256 hash, or null if input is null/blank
     */
    public static String computeSha256(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(
                    input.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
