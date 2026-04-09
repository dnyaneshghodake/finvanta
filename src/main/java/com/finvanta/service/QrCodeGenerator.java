package com.finvanta.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CBS QR Code Generator using ZXing (ISO 18004 compliant).
 *
 * Per Finacle/Temenos Tier-1 CBS deployment standards:
 * - Bank networks are air-gapped (no CDN, no npm, no external JS libraries)
 * - All rendering must be server-side using bundled libraries
 * - ZXing is Apache 2.0 licensed, bundled in the WAR — no runtime internet needed
 *
 * ZXing is the de facto standard for QR in Java banking applications and is the
 * same library used by Google Authenticator itself. It provides full ISO 18004
 * compliance including correct Reed-Solomon error correction, codeword interleaving,
 * format/version information, and optimal mask pattern selection.
 *
 * Per RBI IT Governance Direction 2023 Section 8.4:
 * MFA enrollment QR codes must be generated server-side, rendered as inline
 * data URIs, and not cached or stored (ephemeral, single-use during enrollment).
 */
@Component
public class QrCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(QrCodeGenerator.class);

    /** QR image size in pixels — 300x300 is optimal for mobile camera scanning */
    private static final int QR_SIZE = 300;

    /**
     * Generates a QR code as a Base64-encoded PNG data URI.
     *
     * @param data The data to encode (e.g., otpauth:// URI)
     * @return Base64 data URI string: "data:image/png;base64,..." or null on failure
     */
    public String generateDataUri(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);

            BufferedImage image = renderImage(bitMatrix);
            return toDataUri(image);
        } catch (WriterException e) {
            log.error("QR code encoding failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("QR code generation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Render ZXing BitMatrix to a BufferedImage with crisp black/white modules. */
    private BufferedImage renderImage(BitMatrix bitMatrix) {
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (bitMatrix.get(x, y)) {
                    g.fillRect(x, y, 1, 1);
                }
            }
        }
        g.dispose();
        return image;
    }

    /** Convert BufferedImage to Base64 PNG data URI for inline HTML embedding. */
    private String toDataUri(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
