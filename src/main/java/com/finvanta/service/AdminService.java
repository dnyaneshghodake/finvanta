package com.finvanta.service;

import com.finvanta.domain.entity.ChargeConfig;
import com.finvanta.domain.entity.TransactionLimit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * CBS Admin Service — manages TransactionLimits and ChargeConfigs per Finacle LIMDEF / CHRG_MASTER.
 *
 * All mutations MUST be performed through this service (not directly in controllers).
 * The service enforces:
 * - Multi-tenant isolation (tenantId from TenantContext, validated on every operation)
 * - Maker-checker audit trail (audit events logged for every mutation)
 * - Transactional atomicity (@Transactional on all write operations)
 *
 * Per RBI Internal Controls:
 * - Transaction limits are per-role, per-type amount controls for operational risk
 * - Charge configs with FLAT/PERCENTAGE/SLAB calculation + GST
 */
public interface AdminService {

    // ========================================================================
    // Transaction Limit Operations
    // ========================================================================

    /** List all transaction limits for the current tenant. */
    List<TransactionLimit> listTransactionLimits();

    /**
     * Create a new transaction limit.
     *
     * @throws com.finvanta.util.BusinessException with code "ROLE_REQUIRED" if role is blank
     * @throws com.finvanta.util.BusinessException with code "TXN_TYPE_REQUIRED" if transactionType is blank
     */
    TransactionLimit createTransactionLimit(
            String role,
            String transactionType,
            BigDecimal perTransactionLimit,
            BigDecimal dailyAggregateLimit,
            String description);

    /**
     * Update an existing transaction limit.
     *
     * @throws com.finvanta.util.BusinessException with code "LIMIT_NOT_FOUND" if limit does not exist or belongs to another tenant
     */
    TransactionLimit updateTransactionLimit(
            Long id,
            BigDecimal perTransactionLimit,
            BigDecimal dailyAggregateLimit,
            String description);

    /**
     * Toggle active state of a transaction limit.
     *
     * @throws com.finvanta.util.BusinessException with code "LIMIT_NOT_FOUND" if limit does not exist or belongs to another tenant
     */
    TransactionLimit toggleTransactionLimitActive(Long id);

    // ========================================================================
    // Charge Config Operations
    // ========================================================================

    /** List all charge configs for the current tenant. */
    List<ChargeConfig> listChargeConfigs();

    /**
     * Create a new charge config per Finacle CHRG_MASTER.
     *
     * @throws com.finvanta.util.BusinessException with code "CHARGE_CODE_REQUIRED" if chargeCode is blank
     * @throws com.finvanta.util.BusinessException with code "INVALID_CHARGE_CODE" if format is invalid
     * @throws com.finvanta.util.BusinessException with code "BASE_AMOUNT_REQUIRED" for FLAT without baseAmount
     * @throws com.finvanta.util.BusinessException with code "PERCENTAGE_REQUIRED" for PERCENTAGE without percentage
     * @throws com.finvanta.util.BusinessException with code "SLAB_JSON_REQUIRED" for SLAB without slabJson
     * @throws com.finvanta.util.BusinessException with code "GST_RATE_REQUIRED" if GST applicable without rate
     */
    ChargeConfig createChargeConfig(
            String chargeCode,
            String chargeName,
            String chargeCategory,
            String eventTrigger,
            String calculationType,
            String frequency,
            BigDecimal baseAmount,
            BigDecimal percentage,
            String slabJson,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String currencyCode,
            boolean gstApplicable,
            BigDecimal gstRate,
            String glChargeIncome,
            String glGstPayable,
            boolean waiverAllowed,
            BigDecimal maxWaiverPercent,
            String productCode,
            String channel,
            LocalDate validFrom,
            LocalDate validTo,
            String customerDescription);

    /**
     * Update an existing charge config per Finacle CHRG_MASTER.
     *
     * @throws com.finvanta.util.BusinessException with code "CHARGE_NOT_FOUND" if config does not exist or belongs to another tenant
     */
    ChargeConfig updateChargeConfig(
            Long id,
            String chargeName,
            String chargeCategory,
            String eventTrigger,
            String calculationType,
            String frequency,
            BigDecimal baseAmount,
            BigDecimal percentage,
            String slabJson,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String currencyCode,
            boolean gstApplicable,
            BigDecimal gstRate,
            String glChargeIncome,
            String glGstPayable,
            boolean waiverAllowed,
            BigDecimal maxWaiverPercent,
            String productCode,
            String channel,
            LocalDate validFrom,
            LocalDate validTo,
            String customerDescription);

    /**
     * Toggle active state of a charge config.
     *
     * @throws com.finvanta.util.BusinessException with code "CHARGE_NOT_FOUND" if config does not exist or belongs to another tenant
     */
    ChargeConfig toggleChargeConfigActive(Long id);
}