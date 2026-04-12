package com.finvanta.batch;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ChargeConfig;
import com.finvanta.repository.ChargeConfigRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChargeEngine (Loan) per RBI Fair Lending Code 2023.
 */
@DisplayName("ChargeEngine Tests")
public class ChargeEngineTest {

    @Mock
    private ChargeConfigRepository configRepository;

    @Mock
    private LoanAccountRepository accountRepository;

    @Mock
    private TransactionEngine transactionEngine;

    @Mock
    private ProductGLResolver glResolver;

    @Mock
    private AuditService auditService;

    private ChargeEngine chargeEngine;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // CBS: Set tenant context for multi-tenant charge resolution per Finacle CHRG_MASTER
        TenantContext.setCurrentTenant("DEFAULT");
        // Provide real ObjectMapper for slab JSON parsing — Jackson is a deterministic utility, not a service
        // dependency
        chargeEngine = new ChargeEngine(
                configRepository, accountRepository, transactionEngine, glResolver, auditService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Calculate FLAT charge correctly")
    void testCalculateFlatCharge() {
        // Arrange
        ChargeConfig config = new ChargeConfig();
        config.setChargeCode("LATE_PAYMENT_FEE");
        config.setCalculationType("FLAT");
        config.setBaseAmount(new BigDecimal("500.00"));
        config.setGstApplicable(true);
        config.setGstRate(new BigDecimal("18.00"));
        config.setGlChargeIncome("4002");
        config.setGlGstPayable("2200");
        config.setIsActive(true);

        when(configRepository.findByTenantAndChargeCodeAndProduct("DEFAULT", "LATE_PAYMENT_FEE", "TERM_LOAN"))
                .thenReturn(Optional.of(config));

        // Act
        ChargeEngine.ChargeResult result =
                chargeEngine.calculateCharge("LATE_PAYMENT_FEE", new BigDecimal("100000.00"), "TERM_LOAN");

        // Assert
        assertEquals(new BigDecimal("500.00"), result.chargeAmount());
        assertEquals(new BigDecimal("90.00"), result.gstAmount()); // 500 * 18%
        assertEquals(new BigDecimal("590.00"), result.totalAmount());
        assertEquals("4002", result.glChargeIncome());
        assertEquals("2200", result.glGstPayable());
    }

    @Test
    @DisplayName("Calculate PERCENTAGE charge correctly")
    void testCalculatePercentageCharge() {
        // Arrange
        ChargeConfig config = new ChargeConfig();
        config.setChargeCode("PROCESSING_FEE");
        config.setCalculationType("PERCENTAGE");
        config.setPercentage(new BigDecimal("1.00"));
        config.setGstApplicable(true);
        config.setGstRate(new BigDecimal("18.00"));
        config.setGlChargeIncome("4002");
        config.setGlGstPayable("2200");
        config.setIsActive(true);

        when(configRepository.findByTenantAndChargeCodeAndProduct("DEFAULT", "PROCESSING_FEE", "TERM_LOAN"))
                .thenReturn(Optional.of(config));

        // Act
        ChargeEngine.ChargeResult result =
                chargeEngine.calculateCharge("PROCESSING_FEE", new BigDecimal("100000.00"), "TERM_LOAN");

        // Assert
        assertEquals(new BigDecimal("1000.00"), result.chargeAmount()); // 100000 * 1%
        assertEquals(new BigDecimal("180.00"), result.gstAmount()); // 1000 * 18%
        assertEquals(new BigDecimal("1180.00"), result.totalAmount());
    }

    @Test
    @DisplayName("Reject zero/negative charge base")
    void testRejectZeroChargeBase() {
        // Act & Assert
        assertThrows(
                BusinessException.class,
                () -> chargeEngine.calculateCharge("PROCESSING_FEE", BigDecimal.ZERO, "TERM_LOAN"));
    }

    @Test
    @DisplayName("Reject charge config not found")
    void testChargeConfigNotFound() {
        // Arrange
        when(configRepository.findByTenantAndChargeCodeAndProduct("DEFAULT", "UNKNOWN_CHARGE", "TERM_LOAN"))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
                BusinessException.class,
                () -> chargeEngine.calculateCharge("UNKNOWN_CHARGE", new BigDecimal("100000.00"), "TERM_LOAN"));
    }

    @Test
    @DisplayName("Calculate SLAB charge correctly")
    void testCalculateSlabCharge() {
        // Arrange
        ChargeConfig config = new ChargeConfig();
        config.setChargeCode("STAMP_DUTY");
        config.setCalculationType("SLAB");
        config.setSlabJson(
                "[{\"min\":0,\"max\":100000,\"rate\":0.10}," + "{\"min\":100001,\"max\":500000,\"rate\":0.15},"
                        + "{\"min\":500001,\"max\":10000000,\"rate\":0.20}]");
        config.setGstApplicable(false);
        config.setGlChargeIncome("4002");
        config.setIsActive(true);

        when(configRepository.findByTenantAndChargeCodeAndProduct("DEFAULT", "STAMP_DUTY", "HOME_LOAN"))
                .thenReturn(Optional.of(config));

        // Act
        ChargeEngine.ChargeResult result =
                chargeEngine.calculateCharge("STAMP_DUTY", new BigDecimal("250000.00"), "HOME_LOAN");

        // Assert - 250000 falls in slab [100001, 500000] at 0.15% = 375
        assertEquals(new BigDecimal("375.00"), result.chargeAmount());
        assertEquals(BigDecimal.ZERO, result.gstAmount());
        assertEquals(new BigDecimal("375.00"), result.totalAmount());
    }

    @Test
    @DisplayName("Apply min_amount bound")
    void testApplyMinAmountBound() {
        // Arrange
        ChargeConfig config = new ChargeConfig();
        config.setChargeCode("SMALL_CHARGE");
        config.setCalculationType("PERCENTAGE");
        config.setPercentage(new BigDecimal("0.10"));
        config.setMinAmount(new BigDecimal("100.00"));
        config.setGstApplicable(false);
        config.setGlChargeIncome("4002");
        config.setIsActive(true);

        when(configRepository.findByTenantAndChargeCodeAndProduct("DEFAULT", "SMALL_CHARGE", "TERM_LOAN"))
                .thenReturn(Optional.of(config));

        // Act - 10000 * 0.10% = 10, but min is 100
        ChargeEngine.ChargeResult result =
                chargeEngine.calculateCharge("SMALL_CHARGE", new BigDecimal("10000.00"), "TERM_LOAN");

        // Assert
        assertEquals(new BigDecimal("100.00"), result.chargeAmount());
    }
}
