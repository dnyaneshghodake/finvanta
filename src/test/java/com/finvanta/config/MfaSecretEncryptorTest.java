package com.finvanta.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CBS Security Test: MFA Secret Encryption per RBI IT Governance Direction 2023 Section 8.4.
 *
 * Validates AES-256-GCM encryption/decryption of TOTP secrets at rest.
 * Per NIST SP 800-38D: GCM must provide both confidentiality and integrity.
 * These tests verify that:
 * - Encryption is reversible (round-trip)
 * - Each encryption produces unique ciphertext (random IV)
 * - Tampered ciphertext is detected (GCM authentication tag)
 * - Wrong keys are rejected
 * - Null/blank values pass through without encryption
 */
class MfaSecretEncryptorTest {

    private MfaSecretEncryptor encryptor;

    private static final String TEST_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SAMPLE_SECRET = "JBSWY3DPEHPK3PXP"; // Base32 TOTP secret

    private Environment mockEnvironment() {
        Environment env = mock(Environment.class);
        // CBS: Must match the EXACT argument list in MfaSecretEncryptor.validateEncryptionKey()
        // which calls environment.matchesProfiles("dev", "test") — 2 args, not 3.
        // The "sqlserver" profile was removed from the ephemeral check because sqlserver
        // uses a PERSISTENT database where the default key is a CVE.
        when(env.matchesProfiles("dev", "test")).thenReturn(true);
        return env;
    }

    @BeforeEach
    void setUp() {
        encryptor = new MfaSecretEncryptor(mockEnvironment());
        ReflectionTestUtils.setField(encryptor, "hexKey", TEST_KEY);
    }

    @Test
    @DisplayName("Encrypt then decrypt returns original plaintext")
    void encrypt_decrypt_roundTrip() {
        String encrypted = encryptor.convertToDatabaseColumn(SAMPLE_SECRET);
        String decrypted = encryptor.convertToEntityAttribute(encrypted);

        assertEquals(SAMPLE_SECRET, decrypted);
    }

    @Test
    @DisplayName("Two encryptions of same input produce different ciphertext (random IV)")
    void encrypt_producesUniqueOutput() {
        String encrypted1 = encryptor.convertToDatabaseColumn(SAMPLE_SECRET);
        String encrypted2 = encryptor.convertToDatabaseColumn(SAMPLE_SECRET);

        assertNotEquals(encrypted1, encrypted2, "Each encryption must use a unique random IV");

        // Both must decrypt to the same plaintext
        assertEquals(SAMPLE_SECRET, encryptor.convertToEntityAttribute(encrypted1));
        assertEquals(SAMPLE_SECRET, encryptor.convertToEntityAttribute(encrypted2));
    }

    @Test
    @DisplayName("Decrypt with wrong key throws exception (GCM auth tag failure)")
    void decrypt_withWrongKey_throwsException() {
        String encrypted = encryptor.convertToDatabaseColumn(SAMPLE_SECRET);

        // Create a new encryptor with a different key
        MfaSecretEncryptor wrongKeyEncryptor = new MfaSecretEncryptor(mockEnvironment());
        ReflectionTestUtils.setField(wrongKeyEncryptor, "hexKey",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

        assertThrows(IllegalStateException.class,
                () -> wrongKeyEncryptor.convertToEntityAttribute(encrypted));
    }

    @Test
    @DisplayName("Encrypt null input returns null (passthrough)")
    void encrypt_nullInput_returnsNull() {
        assertNull(encryptor.convertToDatabaseColumn(null));
    }

    @Test
    @DisplayName("Decrypt null input returns null (passthrough)")
    void decrypt_nullInput_returnsNull() {
        assertNull(encryptor.convertToEntityAttribute(null));
    }

    @Test
    @DisplayName("Encrypt blank input returns blank (passthrough)")
    void encrypt_blankInput_returnsBlank() {
        String result = encryptor.convertToDatabaseColumn("   ");
        assertEquals("   ", result);
    }

    @Test
    @DisplayName("Decrypt blank input returns blank (passthrough)")
    void decrypt_blankInput_returnsBlank() {
        String result = encryptor.convertToEntityAttribute("   ");
        assertEquals("   ", result);
    }

    @Test
    @DisplayName("Tampered ciphertext detected by GCM authentication tag")
    void decrypt_tamperedCiphertext_throwsException() {
        String encrypted = encryptor.convertToDatabaseColumn(SAMPLE_SECRET);

        // Tamper with the ciphertext (flip a character in the middle)
        char[] chars = encrypted.toCharArray();
        int mid = chars.length / 2;
        chars[mid] = (chars[mid] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertThrows(IllegalStateException.class,
                () -> encryptor.convertToEntityAttribute(tampered));
    }

    @Test
    @DisplayName("Key with wrong length is rejected")
    void keyValidation_wrongLength_throwsException() {
        MfaSecretEncryptor badKeyEncryptor = new MfaSecretEncryptor(mockEnvironment());
        ReflectionTestUtils.setField(badKeyEncryptor, "hexKey", "0123456789abcdef"); // 16 chars = 8 bytes (too short)

        assertThrows(IllegalStateException.class,
                () -> badKeyEncryptor.convertToDatabaseColumn(SAMPLE_SECRET));
    }

    @Test
    @DisplayName("Production profile with default key fails startup")
    void prodProfile_defaultKey_throwsException() {
        Environment prodEnv = mock(Environment.class);
        // CBS: 2 args matching production code — "sqlserver" is NOT ephemeral
        when(prodEnv.matchesProfiles("dev", "test")).thenReturn(false);

        MfaSecretEncryptor prodEncryptor = new MfaSecretEncryptor(prodEnv);
        ReflectionTestUtils.setField(prodEncryptor, "hexKey", TEST_KEY); // TEST_KEY == DEV_DEFAULT_KEY

        assertThrows(IllegalStateException.class, prodEncryptor::validateEncryptionKey,
                "Production profile with default key must fail startup");
    }

    @Test
    @DisplayName("Dev profile with default key logs warning but does not fail")
    void devProfile_defaultKey_doesNotThrow() {
        Environment devEnv = mock(Environment.class);
        // CBS: 2 args matching production code
        when(devEnv.matchesProfiles("dev", "test")).thenReturn(true);

        MfaSecretEncryptor devEncryptor = new MfaSecretEncryptor(devEnv);
        ReflectionTestUtils.setField(devEncryptor, "hexKey", TEST_KEY);

        assertDoesNotThrow(devEncryptor::validateEncryptionKey,
                "Dev profile with default key should not fail");
    }

    @Test
    @DisplayName("Encrypted output is Base64 encoded and longer than input")
    void encrypt_outputFormat_isBase64() {
        String encrypted = encryptor.convertToDatabaseColumn(SAMPLE_SECRET);

        assertNotNull(encrypted);
        assertNotEquals(SAMPLE_SECRET, encrypted);
        // Base64: IV(12) + ciphertext + tag(16) → always longer than plaintext
        assertTrue(encrypted.length() > SAMPLE_SECRET.length());
        // Verify it's valid Base64
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(encrypted));
    }
}
