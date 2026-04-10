package com.finvanta.config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * CBS Property Encryption/Decryption Utility per RBI IT Governance Direction 2023.
 *
 * Per Finacle/Temenos Tier-1 standards: database credentials and sensitive
 * configuration values must NEVER be stored in plaintext in property files.
 * This utility provides AES-256-GCM encryption for property values.
 *
 * Usage in property files:
 *   spring.datasource.username=ENC(base64-ciphertext)
 *   spring.datasource.password=ENC(base64-ciphertext)
 *
 * The encryption key MUST come from an environment variable:
 *   FINVANTA_DB_ENCRYPTION_KEY=<64-hex-char-key>
 *
 * To encrypt a value (run from command line):
 *   java -cp finvanta.war com.finvanta.config.CbsPropertyDecryptor encrypt <key-hex> <plaintext>
 *
 * To decrypt (for verification):
 *   java -cp finvanta.war com.finvanta.config.CbsPropertyDecryptor decrypt <key-hex> <ciphertext>
 *
 * Algorithm: AES-256-GCM (same as MfaSecretEncryptor)
 *   - 256-bit key (32 bytes, hex-encoded = 64 chars)
 *   - 96-bit IV (12 bytes, randomly generated per encryption)
 *   - 128-bit authentication tag (GCM integrity)
 *   - Output: Base64(IV[12] + ciphertext + tag[16])
 */
public final class CbsPropertyDecryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    static final String ENC_PREFIX = "ENC(";
    static final String ENC_SUFFIX = ")";

    private CbsPropertyDecryptor() {}

    /** Check if a property value is encrypted (wrapped in ENC(...)). */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /** Extract the Base64 ciphertext from ENC(...) wrapper. */
    public static String unwrap(String encValue) {
        return encValue.substring(ENC_PREFIX.length(), encValue.length() - ENC_SUFFIX.length());
    }

    /** Encrypt a plaintext value. Returns Base64(IV + ciphertext + tag). */
    public static String encrypt(String plaintext, String hexKey) {
        try {
            SecretKey key = parseKey(hexKey);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("CBS property encryption failed", e);
        }
    }

    /** Decrypt a Base64(IV + ciphertext + tag) value. */
    public static String decrypt(String base64Ciphertext, String hexKey) {
        try {
            SecretKey key = parseKey(hexKey);
            byte[] decoded = Base64.getDecoder().decode(base64Ciphertext);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "CBS property decryption failed — check FINVANTA_DB_ENCRYPTION_KEY", e);
        }
    }

    private static SecretKey parseKey(String hexKey) {
        if (hexKey == null || hexKey.length() != 64) {
            throw new IllegalStateException(
                    "FINVANTA_DB_ENCRYPTION_KEY must be exactly 64 hex chars (32 bytes). "
                            + "Generate with: openssl rand -hex 32");
        }
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) ((Character.digit(hexKey.charAt(i * 2), 16) << 4)
                    + Character.digit(hexKey.charAt(i * 2 + 1), 16));
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * CLI utility for encrypting/decrypting property values.
     *
     * Usage:
     *   java -cp finvanta.war com.finvanta.config.CbsPropertyDecryptor encrypt <hex-key> <plaintext>
     *   java -cp finvanta.war com.finvanta.config.CbsPropertyDecryptor decrypt <hex-key> <ciphertext>
     *   java -cp finvanta.war com.finvanta.config.CbsPropertyDecryptor genkey
     */
    public static void main(String[] args) {
        if (args.length == 1 && "genkey".equals(args[0])) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            StringBuilder hex = new StringBuilder();
            for (byte b : key) hex.append(String.format("%02x", b));
            System.out.println("Generated key: " + hex);
            System.out.println();
            System.out.println("Set the key as environment variable before starting the application:");
            System.out.println("  PowerShell:      $env:FINVANTA_DB_ENCRYPTION_KEY = \"" + hex + "\"");
            System.out.println("  Command Prompt:  set FINVANTA_DB_ENCRYPTION_KEY=" + hex);
            System.out.println("  Linux/Mac:       export FINVANTA_DB_ENCRYPTION_KEY=" + hex);
            System.out.println("  IntelliJ:        Run > Edit Configurations > Environment Variables > Add FINVANTA_DB_ENCRYPTION_KEY");
            return;
        }
        if (args.length != 3) {
            System.err.println("Usage:");
            System.err.println("  encrypt <hex-key> <plaintext>  — Encrypt a value");
            System.err.println("  decrypt <hex-key> <ciphertext> — Decrypt a value");
            System.err.println("  genkey                         — Generate a new 256-bit key");
            System.exit(1);
        }
        String action = args[0];
        String key = args[1];
        String value = args[2];

        if ("encrypt".equals(action)) {
            String encrypted = encrypt(value, key);
            System.out.println("Encrypted: ENC(" + encrypted + ")");
            System.out.println("Paste this into your properties file.");
        } else if ("decrypt".equals(action)) {
            String decrypted = decrypt(value, key);
            System.out.println("Decrypted: " + decrypted);
        } else {
            System.err.println("Unknown action: " + action);
            System.exit(1);
        }
    }
}
