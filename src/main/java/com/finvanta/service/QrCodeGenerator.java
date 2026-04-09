package com.finvanta.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CBS QR Code Generator — Pure Java, Zero External Dependencies.
 *
 * Per Finacle/Temenos Tier-1 CBS deployment standards:
 * - Bank networks are air-gapped (no CDN, no npm, no external JS libraries)
 * - All rendering must be server-side or use bundled assets
 *
 * Generates QR codes using byte mode encoding sufficient for otpauth:// URIs.
 * Output is a Base64-encoded PNG data URI for embedding in HTML img tags.
 *
 * Per RBI IT Governance Direction 2023 Section 8.4:
 * MFA enrollment QR codes must be generated server-side, rendered as inline
 * data URIs, and not cached or stored (ephemeral, single-use during enrollment).
 */
@Component
public class QrCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(QrCodeGenerator.class);
    private static final int MODULE_SIZE = 8;
    private static final int QUIET_ZONE = 4;

    /** Generates a QR code as a Base64 PNG data URI, or null on failure. */
    public String generateDataUri(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            boolean[][] matrix = encode(data);
            return toDataUri(renderImage(matrix));
        } catch (Exception e) {
            log.error("QR code generation failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean[][] encode(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length;
        int ver, dCw, ecPb, nB;
        if (len <= 14) { ver=1; dCw=16; ecPb=10; nB=1; }
        else if (len <= 26) { ver=2; dCw=28; ecPb=16; nB=1; }
        else if (len <= 42) { ver=3; dCw=44; ecPb=26; nB=1; }
        else if (len <= 62) { ver=4; dCw=64; ecPb=18; nB=2; }
        else if (len <= 84) { ver=5; dCw=86; ecPb=24; nB=2; }
        else if (len <= 106) { ver=6; dCw=108; ecPb=16; nB=4; }
        else if (len <= 122) { ver=7; dCw=124; ecPb=18; nB=4; }
        else if (len <= 152) { ver=8; dCw=152; ecPb=22; nB=4; }
        else throw new IllegalArgumentException("Data too long for QR: " + len + " bytes");

        int sz = 17 + ver * 4;
        int ccBits = ver <= 9 ? 8 : 16;
        StringBuilder bits = new StringBuilder();
        bits.append("0100");
        appBits(bits, len, ccBits);
        for (byte b : bytes) appBits(bits, b & 0xFF, 8);
        int tBits = dCw * 8;
        for (int i = 0; i < Math.min(4, tBits - bits.length()); i++) bits.append('0');
        while (bits.length() % 8 != 0) bits.append('0');
        boolean p = true;
        while (bits.length() < tBits) { appBits(bits, p ? 236 : 17, 8); p = !p; }

        byte[] cw = new byte[dCw];
        for (int i = 0; i < dCw; i++)
            cw[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);

        int dpb = dCw / nB;
        byte[] all = new byte[dCw + nB * ecPb];
        int pos = 0, ePos = dCw;
        for (int b = 0; b < nB; b++) {
            byte[] bl = Arrays.copyOfRange(cw, b * dpb, (b + 1) * dpb);
            System.arraycopy(bl, 0, all, pos, bl.length); pos += bl.length;
            byte[] ec = genEc(bl, ecPb);
            System.arraycopy(ec, 0, all, ePos, ec.length); ePos += ec.length;
        }

        boolean[][] m = new boolean[sz][sz], f = new boolean[sz][sz];
        placeFinder(m, f, 0, 0, sz);
        placeFinder(m, f, sz - 7, 0, sz);
        placeFinder(m, f, 0, sz - 7, sz);
        for (int i = 8; i < sz - 8; i++) {
            m[6][i] = i % 2 == 0; f[6][i] = true;
            m[i][6] = i % 2 == 0; f[i][6] = true;
        }
        if (ver >= 2) {
            int[] ap = alignPos(ver, sz);
            for (int r : ap) for (int c : ap) if (!f[r][c]) placeAlign(m, f, r, c, sz);
        }
        for (int i = 0; i < 8; i++) {
            f[8][i] = true; f[i][8] = true;
            f[8][sz-1-i] = true; f[sz-1-i][8] = true;
        }
        f[8][8] = true; m[sz-8][8] = true; f[sz-8][8] = true;

        int bi = 0, tcb = all.length * 8;
        for (int rt = sz - 1; rt >= 1; rt -= 2) {
            if (rt == 6) rt = 5;
            for (int v = 0; v < sz; v++) for (int j = 0; j < 2; j++) {
                int c = rt - j;
                boolean up = ((rt + 1) / 2) % 2 == 0;
                int r = up ? v : sz - 1 - v;
                if (r < 0 || r >= sz || c < 0 || c >= sz || f[r][c]) continue;
                if (bi < tcb) { m[r][c] = ((all[bi/8] >> (7 - bi%8)) & 1) == 1; bi++; }
            }
        }
        for (int r = 0; r < sz; r++)
            for (int c = 0; c < sz; c++)
                if (!f[r][c] && (r + c) % 2 == 0) m[r][c] = !m[r][c];

        placeFmt(m, 0b101010000010010, sz);
        return m;
    }

    private void appBits(StringBuilder sb, int v, int n) {
        for (int i = n - 1; i >= 0; i--) sb.append((v >> i) & 1);
    }

    private byte[] genEc(byte[] d, int n) {
        int[] g = rsGen(n), r = new int[n];
        for (byte b : d) {
            int lead = (b & 0xFF) ^ r[0];
            System.arraycopy(r, 1, r, 0, n - 1); r[n - 1] = 0;
            for (int j = 0; j < n; j++) r[j] ^= gfMul(g[j], lead);
        }
        byte[] ec = new byte[n];
        for (int i = 0; i < n; i++) ec[i] = (byte) r[i];
        return ec;
    }

    private int gfMul(int a, int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            if ((b & 1) != 0) r ^= a;
            boolean hi = (a & 0x80) != 0;
            a = (a << 1) & 0xFF; if (hi) a ^= 0x11D; b >>= 1;
        }
        return r;
    }

    private int[] rsGen(int deg) {
        int[] p = new int[deg]; p[deg - 1] = 1; int root = 1;
        for (int i = 0; i < deg; i++) {
            for (int j = 0; j < deg; j++) {
                p[j] = gfMul(p[j], root); if (j + 1 < deg) p[j] ^= p[j + 1];
            }
            root = gfMul(root, 2);
        }
        return p;
    }

    private void placeFinder(boolean[][] m, boolean[][] f, int row, int col, int sz) {
        for (int r = -1; r <= 7; r++) for (int c = -1; c <= 7; c++) {
            int mr = row + r, mc = col + c;
            if (mr < 0 || mr >= sz || mc < 0 || mc >= sz) continue;
            m[mr][mc] = (r >= 0 && r <= 6 && (c == 0 || c == 6))
                    || (c >= 0 && c <= 6 && (r == 0 || r == 6))
                    || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
            f[mr][mc] = true;
        }
    }

    private void placeAlign(boolean[][] m, boolean[][] f, int row, int col, int sz) {
        for (int r = -2; r <= 2; r++) for (int c = -2; c <= 2; c++) {
            int mr = row + r, mc = col + c;
            if (mr < 0 || mr >= sz || mc < 0 || mc >= sz) continue;
            m[mr][mc] = Math.abs(r) == 2 || Math.abs(c) == 2 || (r == 0 && c == 0);
            f[mr][mc] = true;
        }
    }

    private int[] alignPos(int ver, int sz) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2, step = (sz - 13) / (n - 1);
        if (step % 2 != 0) step++;
        int[] pos = new int[n]; pos[0] = 6;
        for (int i = n - 1; i >= 1; i--) pos[i] = sz - 7 - (n - 1 - i) * step;
        return pos;
    }

    private void placeFmt(boolean[][] m, int fmt, int sz) {
        for (int i = 0; i <= 5; i++) m[8][i] = ((fmt >> (14 - i)) & 1) == 1;
        m[8][7] = ((fmt >> 8) & 1) == 1;
        m[8][8] = ((fmt >> 7) & 1) == 1;
        m[7][8] = ((fmt >> 6) & 1) == 1;
        for (int i = 0; i <= 5; i++) m[5 - i][8] = ((fmt >> i) & 1) == 1;
        for (int i = 0; i <= 7; i++) m[8][sz - 1 - i] = ((fmt >> i) & 1) == 1;
        for (int i = 0; i <= 6; i++) m[sz - 1 - i][8] = ((fmt >> (14 - i)) & 1) == 1;
    }

    private BufferedImage renderImage(boolean[][] matrix) {
        int sz = matrix.length, imgSz = (sz + QUIET_ZONE * 2) * MODULE_SIZE;
        BufferedImage img = new BufferedImage(imgSz, imgSz, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, imgSz, imgSz);
        g.setColor(Color.BLACK);
        for (int r = 0; r < sz; r++) for (int c = 0; c < sz; c++) if (matrix[r][c])
            g.fillRect((c + QUIET_ZONE) * MODULE_SIZE, (r + QUIET_ZONE) * MODULE_SIZE,
                    MODULE_SIZE, MODULE_SIZE);
        g.dispose();
        return img;
    }

    private String toDataUri(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
