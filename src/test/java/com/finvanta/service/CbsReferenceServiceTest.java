package com.finvanta.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finvanta.util.ReferenceGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CBS Test: CbsReferenceService — DB-backed sequential reference generation.
 *
 * Per Finacle SEQ_MASTER / Temenos EB.SEQUENCE:
 * All persistent CBS reference numbers (CIF, Account, Loan, Collateral) must be
 * globally sequential, deterministic across JVM restarts, and cluster-safe.
 *
 * Validates:
 * - CIF format: 11 pure digits with Luhn check digit
 * - CASA account format: {SB|CA}-{BRANCH}-{6-digit}
 * - Loan account format: LN-{BRANCH}-{6-digit}
 * - Application format: APP-{BRANCH}-{6-digit}
 * - Collateral format: COL-{6-digit}
 * - Sequential numbering via SequenceGeneratorService
 * - Branch-scoped sequences for account numbers
 */
@ExtendWith(MockitoExtension.class)
class CbsReferenceServiceTest {

    @Mock
    private SequenceGeneratorService sequenceGenerator;

    @InjectMocks
    private CbsReferenceService cbsReferenceService;

    @Test
    @DisplayName("CIF is 11 pure digits with valid Luhn check — sequential from DB")
    void generateCustomerNumber_correctFormat() {
        // DB sequence returns 1 (first customer)
        when(sequenceGenerator.nextValue("CIF_SEQ")).thenReturn(1L);

        String cif = cbsReferenceService.generateCustomerNumber(2L);

        assertEquals(11, cif.length(), "CIF must be exactly 11 digits");
        assertTrue(cif.matches("\\d{11}"), "CIF must be pure numeric");
        assertTrue(cif.startsWith("002"), "CIF must start with 3-digit SOL from branchId=2");
        // Serial part should be 0000001 (from DB sequence value 1)
        assertEquals("0000001", cif.substring(3, 10), "Serial must be sequential from DB");
        // Verify Luhn check digit
        String base = cif.substring(0, 10);
        int expectedCheck = ReferenceGenerator.computeLuhn(base);
        assertEquals(expectedCheck, cif.charAt(10) - '0', "Luhn check digit must be valid");
    }

    @Test
    @DisplayName("CIF sequential numbering — second customer gets serial 0000002")
    void generateCustomerNumber_sequential() {
        when(sequenceGenerator.nextValue("CIF_SEQ")).thenReturn(2L);

        String cif = cbsReferenceService.generateCustomerNumber(1L);

        assertEquals("0000002", cif.substring(3, 10), "Second CIF serial must be 0000002");
    }

    @Test
    @DisplayName("CIF with null branchId uses SOL 000")
    void generateCustomerNumber_nullBranch() {
        when(sequenceGenerator.nextValue("CIF_SEQ")).thenReturn(1L);

        String cif = cbsReferenceService.generateCustomerNumber(null);

        assertTrue(cif.startsWith("000"), "Null branchId must produce SOL 000");
    }

    @Test
    @DisplayName("CASA savings account: SB-{BRANCH}-{6-digit} format")
    void generateDepositAccountNumber_savings() {
        when(sequenceGenerator.nextFormattedValue("CASA_SEQ_BR001", 6)).thenReturn("000001");

        String accNo = cbsReferenceService.generateDepositAccountNumber("BR001", true);

        assertEquals("SB-BR001-000001", accNo);
    }

    @Test
    @DisplayName("CASA current account: CA-{BRANCH}-{6-digit} format")
    void generateDepositAccountNumber_current() {
        when(sequenceGenerator.nextFormattedValue("CASA_SEQ_HQ001", 6)).thenReturn("000042");

        String accNo = cbsReferenceService.generateDepositAccountNumber("HQ001", false);

        assertEquals("CA-HQ001-000042", accNo);
    }

    @Test
    @DisplayName("CASA accounts are branch-scoped — different branches use different sequences")
    void generateDepositAccountNumber_branchScoped() {
        when(sequenceGenerator.nextFormattedValue("CASA_SEQ_BR001", 6)).thenReturn("000005");
        when(sequenceGenerator.nextFormattedValue("CASA_SEQ_DEL001", 6)).thenReturn("000003");

        String br001Acc = cbsReferenceService.generateDepositAccountNumber("BR001", true);
        String del001Acc = cbsReferenceService.generateDepositAccountNumber("DEL001", true);

        assertEquals("SB-BR001-000005", br001Acc);
        assertEquals("SB-DEL001-000003", del001Acc);
        // Verify different sequence names were used
        verify(sequenceGenerator).nextFormattedValue("CASA_SEQ_BR001", 6);
        verify(sequenceGenerator).nextFormattedValue("CASA_SEQ_DEL001", 6);
    }

    @Test
    @DisplayName("Loan account: LN-{BRANCH}-{6-digit} format")
    void generateLoanAccountNumber_correctFormat() {
        when(sequenceGenerator.nextFormattedValue("LOAN_SEQ_BR001", 6)).thenReturn("000045");

        String loanNo = cbsReferenceService.generateLoanAccountNumber("BR001");

        assertEquals("LN-BR001-000045", loanNo);
    }

    @Test
    @DisplayName("Application number: APP-{BRANCH}-{6-digit} format")
    void generateApplicationNumber_correctFormat() {
        when(sequenceGenerator.nextFormattedValue("APP_SEQ_HQ001", 6)).thenReturn("000012");

        String appNo = cbsReferenceService.generateApplicationNumber("HQ001");

        assertEquals("APP-HQ001-000012", appNo);
    }

    @Test
    @DisplayName("Collateral reference: COL-{6-digit} format (global, not branch-scoped)")
    void generateCollateralRef_correctFormat() {
        when(sequenceGenerator.nextFormattedValue("COL_SEQ", 6)).thenReturn("000056");

        String colRef = cbsReferenceService.generateCollateralRef();

        assertEquals("COL-000056", colRef);
    }

    @Test
    @DisplayName("All generated references fit within VARCHAR(40) column width")
    void allReferences_fitWithinColumnWidth() {
        when(sequenceGenerator.nextValue("CIF_SEQ")).thenReturn(9999999L);
        when(sequenceGenerator.nextFormattedValue(anyString(), eq(6))).thenReturn("999999");

        String cif = cbsReferenceService.generateCustomerNumber(999L);
        String casa = cbsReferenceService.generateDepositAccountNumber("BRANCH01", true);
        String loan = cbsReferenceService.generateLoanAccountNumber("BRANCH01");
        String app = cbsReferenceService.generateApplicationNumber("BRANCH01");
        String col = cbsReferenceService.generateCollateralRef();

        assertTrue(cif.length() <= 40, "CIF length " + cif.length());
        assertTrue(casa.length() <= 40, "CASA length " + casa.length());
        assertTrue(loan.length() <= 40, "Loan length " + loan.length());
        assertTrue(app.length() <= 40, "App length " + app.length());
        assertTrue(col.length() <= 40, "Col length " + col.length());
    }
}
