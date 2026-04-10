package com.finvanta.config;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * CBS MFA Secret Encryption Converter per RBI IT Governance Direction 2023 Section 8.4.
 *
 * Encrypts TOTP secrets at rest using AES-256-GCM (authenticated encryption).
 * This prevents a database dump from exposing MFA secrets that would allow
 * an attacker to generate valid TOTP codes for every enrolled user.
 *
 * <h3>Algorithm: AES-256-GCM</h3>
 * <ul>
 *   <li>256-bit key (32 bytes) — configurable via {@code mfa.encryption.key}</li>
 *   <li>96-bit IV (12 bytes) — randomly generated per encryption, prepended to ciphertext</li>
 *   <li>128-bit authentication tag — GCM provides integrity + confidentiality</li>
 *   <li>Output format: Base64(IV + ciphertext + tag)</li>
 * </ul>
 *
 * <h3>Key Management</h3>
 * <ul>
 *   <li>DEV: Key from {@code mfa.encryption.key} property (hex-encoded, 64 chars)</li>
 *   <li>PROD: Key MUST come from AWS KMS / HashiCorp Vault / HSM — NEVER from properties file</li>
 * </ul>
 *
 * <h3>Per Finacle USER_MASTER / Temenos USER:</h3>
 * Authentication credentials (passwords, MFA secrets, API keys) stored in the user
 * master table must be encrypted at rest. Finacle uses DBMS_CRYPTO (Oracle TDE),
 * Temenos uses field-level encryption. This converter provides equivalent protection
 * at the JPA layer, transparent to all service code.
 *
 * @see com.finvanta.domain.entity.AppUser#mfaSecret
 */
@Component
@Converter
public class MfaSecretEncryptor implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(MfaSecretEncryptor.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits per NIST SP 800-38D
    private static final int GCM_TAG_LENGTH = 128; // 128-bit authentication tag

    /** The well-known default key — used ONLY for dev/test H2 seed data compatibility. */
    private static final String DEV_DEFAULT_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /**
     * Encryption key (hex-encoded, 64 characters = 32 bytes = 256 bits).
     * DEV default: deterministic key for H2 seed data compatibility.
     * PROD: MUST be overridden via environment variable or secrets manager.
     *
     * To generate a production key:
     *   openssl rand -hex 32
     */
    @Value("${mfa.encryption.key:" + DEV_DEFAULT_KEY + "}")
    private String hexKey;

    private final Environment environment;

    public MfaSecretEncryptor(Environment environment) {
        this.environment = environment;
    }

    /**
     * CBS SECURITY: Startup validation per RBI IT Governance Direction 2023 Section 8.4.
     *
     * In production, the default deterministic key is a CVE — any attacker who reads
     * the source code can decrypt every MFA secret in the database. This check:
     *   - DEV/TEST profile: logs a warning (acceptable for H2 seed data)
     *   - PROD/any other profile: FAILS STARTUP to prevent deployment with insecure key
     *
     * Per Finacle/Temenos: encryption keys for authentication credentials must come
     * from HSM/KMS/Vault in production — never from source code or properties files.
     */
    @PostConstruct
    void validateEncryptionKey() {
        boolean isDevOrTest = environment.matchesProfiles("dev", "test", "sqlserver");
        if (DEV_DEFAULT_KEY.equals(hexKey)) {
            if (isDevOrTest) {
                log.warn("CBS SECURITY: MFA encryption using DEFAULT DEV KEY. "
                        + "This is acceptable for dev/test only. "
                        + "PROD MUST override mfa.encryption.key via environment variable or secrets manager.");
            } else {
                log.error("CBS SECURITY VIOLATION: MFA encryption key is the DEFAULT DEV KEY in non-dev profile. "
                        + "This is a CVE — any attacker can decrypt all MFA secrets. "
                        + "Set mfa.encryption.key via environment variable: export MFA_ENCRYPTION_KEY=$(openssl rand -hex 32)");
                throw new IllegalStateException(
                        "FATAL: MFA encryption key must be overridden in production. "
                                + "Set mfa.encryption.key via environment variable or secrets manager.");
            }
        } else {
            log.info("CBS SECURITY: MFA encryption key configured (non-default).");
        }
    }

    private SecretKey getKey() {
        byte[] keyBytes = hexToBytes(hexKey);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "MFA encryption key must be exactly 32 bytes (64 hex chars). Got: " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts the MFA secret before persisting to database.
     * Output: Base64(IV[12] + ciphertext + GCM_tag[16])
     * Null/blank values pass through unencrypted (no secret to protect).
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        try {
            SecretKey key = getKey();
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext: IV[12] + encrypted[N] + tag[16]
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            log.error("MFA secret encryption failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to encrypt MFA secret", e);
        }
    }

    /**
     * Decrypts the MFA secret when reading from database.
     * Input: Base64(IV[12] + ciphertext + GCM_tag[16])
     * Null/blank values pass through (no secret stored).
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        try {
            SecretKey key = getKey();
            byte[] decoded = Base64.getDecoder().decode(dbData);

            // Extract IV (first 12 bytes) and ciphertext+tag (remainder)
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("MFA secret decryption failed — possible key mismatch or data corruption: {}", e.getMessage());
            throw new IllegalStateException("Failed to decrypt MFA secret", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
