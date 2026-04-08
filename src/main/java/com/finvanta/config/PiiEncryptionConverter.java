package com.finvanta.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA AttributeConverter for PII field encryption at rest.
 *
 * Per RBI Master Direction on IT Governance, Risk Management and Controls (2023):
 * "Banks shall ensure that sensitive customer data including PAN, Aadhaar,
 * and biometric data is encrypted at rest using industry-standard algorithms."
 *
 * Implementation:
 * - Algorithm: AES-256-GCM (authenticated encryption — tamper-resistant)
 * - Each value gets a unique random 12-byte IV (nonce) — prepended to ciphertext
 * - Storage format: Base64(IV + GCM_TAG + CIPHERTEXT)
 * - Key source: Environment variable FINVANTA_PII_KEY (32-byte hex string)
 *
 * Per Finacle/Temenos PII standards:
 * - Encryption is transparent to application code (JPA converter)
 * - Null values pass through unencrypted (nullable PII fields)
 * - Empty strings pass through unencrypted
 * - Key rotation: re-encrypt all PII fields with new key via batch job
 *
 * IMPORTANT: In production, use a Hardware Security Module (HSM) or
 * AWS KMS / Azure Key Vault for key management. The environment variable
 * approach is for development/testing only.
 *
 * Usage on entity fields:
 *   @Convert(converter = PiiEncryptionConverter.class)
 *   @Column(name = "pan_number", length = 100) // expanded for ciphertext
 *   private String panNumber;
 */
@Converter
public class PiiEncryptionConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptionConverter.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits per NIST SP 800-38D
    private static final int GCM_TAG_LENGTH = 128; // 128-bit authentication tag

    /**
     * Default key for development/testing ONLY.
     * Production MUST set FINVANTA_PII_KEY environment variable.
     * 32 bytes = 256-bit AES key, hex-encoded = 64 hex chars.
     */
    private static final String DEFAULT_DEV_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        try {
            SecretKeySpec keySpec = getKeySpec();
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext: IV(12) + CIPHERTEXT+TAG
            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Per RBI IT Governance Direction 2023: PII must NEVER be stored in plaintext.
            // Fail the operation rather than silently persisting unencrypted data.
            throw new RuntimeException(
                    "PII encryption failed — refusing to store plaintext. " + "Check FINVANTA_PII_KEY configuration.",
                    e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);

            // Minimum size: IV (12 bytes) + GCM tag (16 bytes) + at least 1 byte ciphertext = 29 bytes
            // If decoded data is too short, it's plaintext that happens to be valid Base64
            // (e.g., PAN "ABCDE1234F" decodes to 7 bytes — too short for IV extraction)
            if (combined.length < GCM_IV_LENGTH + 16) {
                log.debug(
                        "PII field too short for AES-GCM ({} bytes) — treating as plaintext: {}",
                        combined.length,
                        maskPii(dbData));
                return dbData;
            }

            // Extract IV and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            SecretKeySpec keySpec = getKeySpec();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not Base64 — likely plaintext from before encryption was enabled
            log.debug("PII field appears to be plaintext (pre-encryption data): {}", maskPii(dbData));
            return dbData;
        } catch (Exception e) {
            // Per RBI IT Governance Direction 2023 Section 8.3: encryption failures must
            // be fail-fast. Returning ciphertext as if it were a PAN/Aadhaar would silently
            // expose garbled data to users and downstream systems.
            //
            // If this exception fires in production, it indicates:
            //   1. Key rotation without re-encryption batch (most common)
            //   2. Corrupted ciphertext in DB
            //   3. Algorithm mismatch between encryption and decryption
            //
            // Resolution: Run PII re-encryption batch with the correct key, or restore
            // the previous key via FINVANTA_PII_KEY environment variable.
            log.error(
                    "PII decryption failed — REFUSING to return corrupted data. "
                            + "Possible key rotation without re-encryption. "
                            + "Set correct FINVANTA_PII_KEY or run re-encryption batch.",
                    e);
            throw new RuntimeException(
                    "PII decryption failed — data integrity compromised. "
                            + "Check FINVANTA_PII_KEY configuration and run re-encryption if key was rotated.",
                    e);
        }
    }

    private SecretKeySpec getKeySpec() {
        String hexKey = System.getenv("FINVANTA_PII_KEY");

        // If key is explicitly set but malformed — always fail (any environment)
        if (hexKey != null && hexKey.length() != 64) {
            throw new RuntimeException("FINVANTA_PII_KEY is set but invalid: expected 64 hex chars, got "
                    + hexKey.length() + " chars. Cannot encrypt PII data.");
        }

        if (hexKey == null) {
            // Per RBI IT Governance Direction 2023: production systems MUST NOT use
            // a hardcoded encryption key. The default key is for development/testing ONLY.
            String profile = System.getenv("SPRING_PROFILES_ACTIVE");
            if (profile != null && (profile.contains("prod") || profile.contains("staging"))) {
                throw new RuntimeException("FINVANTA_PII_KEY environment variable is required in " + profile
                        + " profile. Cannot use default dev key for PII encryption.");
            }
            log.warn("FINVANTA_PII_KEY not set — using default dev key. "
                    + "This is ONLY acceptable in development/test environments.");
            hexKey = DEFAULT_DEV_KEY;
        }
        byte[] keyBytes = hexStringToBytes(hexKey);
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /** Masks PII for safe logging — shows only last 4 chars */
    private String maskPii(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }
}
