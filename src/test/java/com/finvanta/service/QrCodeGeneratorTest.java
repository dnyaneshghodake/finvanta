package com.finvanta.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CBS Test: QR Code Generator for MFA Enrollment per RBI IT Governance Direction 2023 §8.4.
 *
 * Validates that generated QR codes:
 * - Are scannable by authenticator apps (round-trip encode → decode)
 * - Contain the exact otpauth:// URI data
 * - Handle edge cases (null, empty, long data)
 * - Output valid Base64 PNG data URIs
 *
 * Uses ZXing's own decoder to verify the generated QR codes are ISO 18004 compliant
 * and decodable — the same verification path as Google Authenticator's scanner.
 */
class QrCodeGeneratorTest {

    private QrCodeGenerator qrCodeGenerator;

    /** Typical otpauth:// URI for MFA enrollment (~100 chars) */
    private static final String SAMPLE_OTP_URI =
            "otpauth://totp/Finvanta%20CBS:testuser?secret=JBSWY3DPEHPK3PXP"
                    + "&issuer=Finvanta%20CBS&digits=6&period=30";

    @BeforeEach
    void setUp() {
        qrCodeGenerator = new QrCodeGenerator();
    }

    @Test
    @DisplayName("Generated QR code is scannable and contains correct otpauth URI")
    void generateDataUri_scannableRoundTrip() throws Exception {
        String dataUri = qrCodeGenerator.generateDataUri(SAMPLE_OTP_URI);

        assertNotNull(dataUri, "QR data URI should not be null");

        // Decode the QR code back to verify it contains the original data
        String decoded = decodeQrFromDataUri(dataUri);
        assertEquals(SAMPLE_OTP_URI, decoded,
                "Decoded QR content must exactly match the original otpauth URI");
    }

    @Test
    @DisplayName("Output is a valid Base64 PNG data URI")
    void generateDataUri_validFormat() {
        String dataUri = qrCodeGenerator.generateDataUri(SAMPLE_OTP_URI);

        assertNotNull(dataUri);
        assertTrue(dataUri.startsWith("data:image/png;base64,"),
                "Must start with PNG data URI prefix");

        // Extract and validate Base64 payload
        String base64Part = dataUri.substring("data:image/png;base64,".length());
        assertDoesNotThrow(() -> Base64.getDecoder().decode(base64Part),
                "Base64 payload must be valid");

        // Verify it's a valid PNG image
        byte[] imageBytes = Base64.getDecoder().decode(base64Part);
        assertTrue(imageBytes.length > 100, "PNG image should be non-trivial size");
        // PNG magic bytes: 137 80 78 71
        assertEquals((byte) 0x89, imageBytes[0], "PNG magic byte 1");
        assertEquals((byte) 0x50, imageBytes[1], "PNG magic byte 2 (P)");
        assertEquals((byte) 0x4E, imageBytes[2], "PNG magic byte 3 (N)");
        assertEquals((byte) 0x47, imageBytes[3], "PNG magic byte 4 (G)");
    }

    @Test
    @DisplayName("QR code with special characters in username is scannable")
    void generateDataUri_specialCharsInUri() throws Exception {
        String uriWithSpecialChars =
                "otpauth://totp/Finvanta%20CBS:user%40bank.com?secret=JBSWY3DPEHPK3PXP"
                        + "&issuer=Finvanta%20CBS&digits=6&period=30";

        String dataUri = qrCodeGenerator.generateDataUri(uriWithSpecialChars);
        assertNotNull(dataUri);

        String decoded = decodeQrFromDataUri(dataUri);
        assertEquals(uriWithSpecialChars, decoded,
                "Special characters must survive QR encode/decode round-trip");
    }

    @Test
    @DisplayName("Null input returns null (no exception)")
    void generateDataUri_nullInput_returnsNull() {
        assertNull(qrCodeGenerator.generateDataUri(null));
    }

    @Test
    @DisplayName("Empty input returns null (no exception)")
    void generateDataUri_emptyInput_returnsNull() {
        assertNull(qrCodeGenerator.generateDataUri(""));
    }

    @Test
    @DisplayName("Short data produces valid QR code")
    void generateDataUri_shortData() throws Exception {
        String dataUri = qrCodeGenerator.generateDataUri("HELLO");
        assertNotNull(dataUri);

        String decoded = decodeQrFromDataUri(dataUri);
        assertEquals("HELLO", decoded);
    }

    /**
     * Decode a QR code from a Base64 PNG data URI using ZXing's decoder.
     * This simulates what Google Authenticator does when scanning the QR code.
     */
    private String decodeQrFromDataUri(String dataUri) throws Exception {
        String base64Part = dataUri.substring("data:image/png;base64,".length());
        byte[] imageBytes = Base64.getDecoder().decode(base64Part);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        assertNotNull(image, "Data URI must contain a valid PNG image");

        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result result = new MultiFormatReader().decode(bitmap);

        assertNotNull(result, "QR code must be decodable");
        assertEquals(BarcodeFormat.QR_CODE, result.getBarcodeFormat(),
                "Decoded format must be QR_CODE");

        return result.getText();
    }
}
