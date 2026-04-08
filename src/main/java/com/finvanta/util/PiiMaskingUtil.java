package com.finvanta.util;

/**
 * CBS PII (Personally Identifiable Information) Masking Utility.
 *
 * Per RBI IT Governance Direction 2023 and UIDAI Aadhaar Act 2016:
 * - PAN must be masked in all UI/logs/reports (show only last 4 chars)
 * - Aadhaar must be masked (show only last 4 digits) per UIDAI Virtual ID guidelines
 * - Mobile numbers must be partially masked (show only last 4 digits)
 * - Email addresses must be partially masked (show first 2 chars + domain)
 * - Account numbers must be partially masked in customer-facing outputs
 *
 * Usage:
 *   PiiMaskingUtil.maskPan("ABCDE1234F")     -> "XXXXXX234F"
 *   PiiMaskingUtil.maskAadhaar("123456789012") -> "XXXXXXXX9012"
 *   PiiMaskingUtil.maskMobile("9876543210")    -> "XXXXXX3210"
 *   PiiMaskingUtil.maskEmail("john@example.com") -> "jo***@example.com"
 *   PiiMaskingUtil.maskAccountNumber("ACC00123456") -> "XXXXXXX3456"
 *
 * IMPORTANT: This utility is for DISPLAY/LOGGING only. The actual data in the
 * database remains encrypted via PiiEncryptionConverter (AES-256-GCM at rest).
 * Masking is applied at the presentation layer to prevent PII exposure in:
 *   - Application logs (SLF4J/Logback)
 *   - JSP views (use PiiMaskingUtil in JSTL expressions)
 *   - Error messages (BusinessException descriptions)
 *   - API responses (if REST endpoints are added)
 */
public final class PiiMaskingUtil {

    private PiiMaskingUtil() {}

    /**
     * Masks PAN number: shows only last 4 characters.
     * Input: "ABCDE1234F" -> Output: "XXXXXX234F"
     */
    public static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        int visibleChars = 4;
        return "X".repeat(pan.length() - visibleChars) + pan.substring(pan.length() - visibleChars);
    }

    /**
     * Masks Aadhaar number: shows only last 4 digits per UIDAI guidelines.
     * Input: "123456789012" -> Output: "XXXXXXXX9012"
     */
    public static String maskAadhaar(String aadhaar) {
        if (aadhaar == null || aadhaar.length() < 4) return "****";
        int visibleChars = 4;
        return "X".repeat(aadhaar.length() - visibleChars)
            + aadhaar.substring(aadhaar.length() - visibleChars);
    }

    /**
     * Masks mobile number: shows only last 4 digits.
     * Input: "9876543210" -> Output: "XXXXXX3210"
     */
    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 4) return "****";
        int visibleChars = 4;
        return "X".repeat(mobile.length() - visibleChars)
            + mobile.substring(mobile.length() - visibleChars);
    }

    /**
     * Masks email address: shows first 2 characters + domain.
     * Input: "john@example.com" -> Output: "jo***@example.com"
     */
    public static String maskEmail(String email) {
        if (email == null) return "****";
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "****";
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        int visibleChars = Math.min(2, local.length());
        return local.substring(0, visibleChars) + "***" + domain;
    }

    /**
     * Masks account number: shows only last 4 characters.
     * Input: "ACC00123456" -> Output: "XXXXXXX3456"
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) return "****";
        int visibleChars = 4;
        return "X".repeat(accountNumber.length() - visibleChars)
            + accountNumber.substring(accountNumber.length() - visibleChars);
    }

    /**
     * Generic masking: shows only last N characters.
     * Useful for any sensitive field not covered by specific methods.
     */
    public static String maskLastN(String value, int visibleChars) {
        if (value == null || value.length() <= visibleChars) return value;
        return "X".repeat(value.length() - visibleChars)
            + value.substring(value.length() - visibleChars);
    }
}
