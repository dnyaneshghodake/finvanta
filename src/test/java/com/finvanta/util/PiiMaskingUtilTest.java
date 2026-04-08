package com.finvanta.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PiiMaskingUtil — validates PAN, Aadhaar, mobile, email,
 * and account number masking per RBI IT Governance Direction 2023.
 */
class PiiMaskingUtilTest {

    @Test
    void testMaskPan() {
        assertEquals("XXXXXX234F", PiiMaskingUtil.maskPan("ABCDE1234F"));
        assertEquals("****", PiiMaskingUtil.maskPan(null));
        assertEquals("****", PiiMaskingUtil.maskPan("AB"));
        assertEquals("X1234", PiiMaskingUtil.maskPan("A1234"));
    }

    @Test
    void testMaskAadhaar() {
        assertEquals("XXXXXXXX9012", PiiMaskingUtil.maskAadhaar("123456789012"));
        assertEquals("****", PiiMaskingUtil.maskAadhaar(null));
        assertEquals("****", PiiMaskingUtil.maskAadhaar("12"));
    }

    @Test
    void testMaskMobile() {
        assertEquals("XXXXXX3210", PiiMaskingUtil.maskMobile("9876543210"));
        assertEquals("****", PiiMaskingUtil.maskMobile(null));
        assertEquals("1234", PiiMaskingUtil.maskMobile("1234"));
    }

    @Test
    void testMaskEmail() {
        assertEquals("jo***@example.com", PiiMaskingUtil.maskEmail("john@example.com"));
        assertEquals("a***@b.com", PiiMaskingUtil.maskEmail("a@b.com"));
        assertEquals("****", PiiMaskingUtil.maskEmail(null));
        assertEquals("****", PiiMaskingUtil.maskEmail("noemailformat"));
    }

    @Test
    void testMaskAccountNumber() {
        assertEquals("XXXXXXX3456", PiiMaskingUtil.maskAccountNumber("ACC00123456"));
        assertEquals("****", PiiMaskingUtil.maskAccountNumber(null));
        assertEquals("1234", PiiMaskingUtil.maskAccountNumber("1234"));
    }

    @Test
    void testMaskLastN() {
        assertEquals("XXXXXXX3456", PiiMaskingUtil.maskLastN("ACC00123456", 4));
        assertEquals("XXXXXXXX56", PiiMaskingUtil.maskLastN("ACC00123456", 2));
        assertNull(PiiMaskingUtil.maskLastN(null, 4));
        assertEquals("AB", PiiMaskingUtil.maskLastN("AB", 4));
    }
}
