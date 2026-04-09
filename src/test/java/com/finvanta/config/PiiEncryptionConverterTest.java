package com.finvanta.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PII Encryption Converter Tests.
 *
 * Validates AES-256-GCM encryption/decryption for PAN and Aadhaar fields
 * per RBI Master Direction on IT Governance 2023.
 */
class PiiEncryptionConverterTest {

    private PiiEncryptionConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PiiEncryptionConverter();
    }

    @Test
    @DisplayName("Encrypt and decrypt PAN number roundtrip")
    void panRoundtrip() {
        String pan = "ABCDE1234F";
        String encrypted = converter.convertToDatabaseColumn(pan);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertNotEquals(pan, encrypted, "Encrypted value must differ from plaintext");
        assertEquals(pan, decrypted, "Decrypted value must match original");
    }

    @Test
    @DisplayName("Encrypt and decrypt Aadhaar number roundtrip")
    void aadhaarRoundtrip() {
        String aadhaar = "123456789012";
        String encrypted = converter.convertToDatabaseColumn(aadhaar);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertNotEquals(aadhaar, encrypted);
        assertEquals(aadhaar, decrypted);
    }

    @Test
    @DisplayName("Null passes through unchanged")
    void nullPassthrough() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    @DisplayName("Empty string passes through unchanged")
    void emptyPassthrough() {
        assertEquals("", converter.convertToDatabaseColumn(""));
        assertEquals("", converter.convertToEntityAttribute(""));
    }

    @Test
    @DisplayName("Same plaintext produces different ciphertext (unique IV per encryption)")
    void uniqueIvPerEncryption() {
        String pan = "ABCDE1234F";
        String encrypted1 = converter.convertToDatabaseColumn(pan);
        String encrypted2 = converter.convertToDatabaseColumn(pan);

        assertNotEquals(encrypted1, encrypted2, "Each encryption must produce different ciphertext (unique IV)");

        // Both must decrypt to the same value
        assertEquals(pan, converter.convertToEntityAttribute(encrypted1));
        assertEquals(pan, converter.convertToEntityAttribute(encrypted2));
    }

    @Test
    @DisplayName("Plaintext data (pre-encryption) is returned as-is on decrypt")
    void plaintextFallback() {
        // Pre-encryption data that isn't valid Base64 should pass through
        String plainPan = "ABCDE1234F";
        String result = converter.convertToEntityAttribute(plainPan);
        assertEquals(plainPan, result, "Non-Base64 plaintext should be returned as-is for backward compatibility");
    }

    @Test
    @DisplayName("Encrypted ciphertext fits in VARCHAR(100) column")
    void ciphertextFitsColumn() {
        // PAN (10 chars) and Aadhaar (12 chars) — encrypted should fit in 100
        String pan = "ABCDE1234F";
        String aadhaar = "123456789012";

        String encryptedPan = converter.convertToDatabaseColumn(pan);
        String encryptedAadhaar = converter.convertToDatabaseColumn(aadhaar);

        assertTrue(
                encryptedPan.length() <= 100,
                "Encrypted PAN length " + encryptedPan.length() + " exceeds VARCHAR(100)");
        assertTrue(
                encryptedAadhaar.length() <= 100,
                "Encrypted Aadhaar length " + encryptedAadhaar.length() + " exceeds VARCHAR(100)");
    }
}
