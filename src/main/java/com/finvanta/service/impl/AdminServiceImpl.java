package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ChargeConfig;
import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.ChargeConfigRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.service.AdminService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Admin Service Implementation — TransactionLimits and ChargeConfigs.
 *
 * All mutations route through this service. The service:
 * 1. Enforces multi-tenant isolation (re-validates tenantId from TenantContext on every lookup)
 * 2. Logs audit events for every mutation
 * 3. Provides @Transactional atomicity
 *
 * Per RBI Internal Controls / Finacle LIMDEF / CHRG_MASTER:
 * - Transaction limits: per-role, per-type amount controls
 * - Charge configs: FLAT/PERCENTAGE/SLAB + GST
 */
@Service
public class AdminServiceImpl implements AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    private final TransactionLimitRepository limitRepository;
    private final ChargeConfigRepository chargeConfigRepository;
    private final AuditService auditService;

    public AdminServiceImpl(
            TransactionLimitRepository limitRepository,
            ChargeConfigRepository chargeConfigRepository,
            AuditService auditService) {
        this.limitRepository = limitRepository;
        this.chargeConfigRepository = chargeConfigRepository;
        this.auditService = auditService;
    }

    // ========================================================================
    // Transaction Limit Operations
    // ========================================================================

    @Override
    public List<TransactionLimit> listTransactionLimits() {
        String tenantId = TenantContext.getCurrentTenant();
        return limitRepository.findByTenantIdOrderByRoleAscTransactionTypeAsc(tenantId);
    }

    @Override
    @Transactional
    public TransactionLimit createTransactionLimit(
            String role,
            String transactionType,
            BigDecimal perTransactionLimit,
            BigDecimal dailyAggregateLimit,
            String description) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        if (role == null || role.isBlank()) {
            throw new BusinessException("ROLE_REQUIRED", "Role is mandatory.");
        }
        if (transactionType == null || transactionType.isBlank()) {
            throw new BusinessException("TXN_TYPE_REQUIRED", "Transaction type is mandatory.");
        }

        TransactionLimit tl = new TransactionLimit();
        tl.setTenantId(tenantId);
        tl.setRole(role);
        tl.setTransactionType(transactionType);
        tl.setPerTransactionLimit(perTransactionLimit);
        tl.setDailyAggregateLimit(dailyAggregateLimit);
        tl.setDescription(description);
        tl.setActive(true);
        tl.setCreatedBy(user);

        TransactionLimit saved = limitRepository.save(tl);

        auditService.logEvent(
                "TransactionLimit",
                saved.getId(),
                "LIMIT_CREATED",
                null,
                saved.getRole() + "/" + saved.getTransactionType(),
                "LIMIT_CONFIG",
                "Limit created: " + saved.getRole() + "/" + saved.getTransactionType()
                        + " | Per-txn: " + saved.getPerTransactionLimit()
                        + " | Daily: " + saved.getDailyAggregateLimit()
                        + " | By: " + user);

        log.info("TransactionLimit created: id={}, role={}, type={}, perTxn={}, daily={}, by={}",
                saved.getId(), role, transactionType, perTransactionLimit, dailyAggregateLimit, user);

        return saved;
    }

    @Override
    @Transactional
    public TransactionLimit updateTransactionLimit(
            Long id,
            BigDecimal perTransactionLimit,
            BigDecimal dailyAggregateLimit,
            String description) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        TransactionLimit tl = limitRepository.findById(id)
                .filter(l -> l.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "LIMIT_NOT_FOUND",
                        "Transaction limit not found: " + id));

        String before = tl.getPerTransactionLimit() + "|" + tl.getDailyAggregateLimit();

        tl.setPerTransactionLimit(perTransactionLimit);
        tl.setDailyAggregateLimit(dailyAggregateLimit);
        tl.setDescription(description);
        tl.setUpdatedBy(user);

        TransactionLimit saved = limitRepository.save(tl);

        String after = saved.getPerTransactionLimit() + "|" + saved.getDailyAggregateLimit();
        auditService.logEvent(
                "TransactionLimit",
                saved.getId(),
                "LIMIT_UPDATED",
                before,
                after,
                "LIMIT_CONFIG",
                "Limit updated: " + saved.getRole() + "/" + saved.getTransactionType() + " by " + user);

        log.info("TransactionLimit updated: id={}, role={}, type={}, by={}",
                saved.getId(), saved.getRole(), saved.getTransactionType(), user);

        return saved;
    }

    @Override
    @Transactional
    public TransactionLimit toggleTransactionLimitActive(Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        TransactionLimit tl = limitRepository.findById(id)
                .filter(l -> l.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "LIMIT_NOT_FOUND",
                        "Transaction limit not found: " + id));

        boolean newState = !tl.isActive();
        tl.setActive(newState);
        tl.setUpdatedBy(user);

        TransactionLimit saved = limitRepository.save(tl);

        auditService.logEvent(
                "TransactionLimit",
                saved.getId(),
                newState ? "LIMIT_ACTIVATED" : "LIMIT_DEACTIVATED",
                String.valueOf(!newState),
                String.valueOf(newState),
                "LIMIT_CONFIG",
                "Limit " + (newState ? "activated" : "deactivated") + ": "
                        + saved.getRole() + "/" + saved.getTransactionType()
                        + " by " + user);

        log.info("TransactionLimit toggled: id={}, active={}, role={}, type={}, by={}",
                saved.getId(), newState, saved.getRole(), saved.getTransactionType(), user);

        return saved;
    }

    // ========================================================================
    // Charge Config Operations
    // ========================================================================

    @Override
    public List<ChargeConfig> listChargeConfigs() {
        String tenantId = TenantContext.getCurrentTenant();
        return chargeConfigRepository.findByTenantIdOrderByChargeCode(tenantId);
    }

    @Override
    @Transactional
    public ChargeConfig createChargeConfig(
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
            String customerDescription) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        // CBS Validation per Finacle CHRG_MASTER
        if (chargeCode == null || chargeCode.isBlank()) {
            throw new BusinessException("CHARGE_CODE_REQUIRED", "Charge code is mandatory.");
        }
        if (!chargeCode.matches("^[A-Z0-9_]{2,50}$")) {
            throw new BusinessException(
                    "INVALID_CHARGE_CODE",
                    "Uppercase alphanumeric + underscore, 2-50 chars.");
        }
        if ("FLAT".equals(calculationType) && (baseAmount == null || baseAmount.signum() <= 0)) {
            throw new BusinessException(
                    "BASE_AMOUNT_REQUIRED",
                    "Base amount is mandatory for FLAT charges.");
        }
        if ("PERCENTAGE".equals(calculationType) && (percentage == null || percentage.signum() <= 0)) {
            throw new BusinessException(
                    "PERCENTAGE_REQUIRED",
                    "Percentage is mandatory for PERCENTAGE charges.");
        }
        if ("SLAB".equals(calculationType) && (slabJson == null || slabJson.isBlank())) {
            throw new BusinessException(
                    "SLAB_JSON_REQUIRED",
                    "Slab JSON is mandatory for SLAB charges.");
        }
        if (gstApplicable && (gstRate == null || gstRate.signum() <= 0)) {
            throw new BusinessException(
                    "GST_RATE_REQUIRED",
                    "GST rate is mandatory when GST is applicable.");
        }

        ChargeConfig cc = new ChargeConfig();
        cc.setTenantId(tenantId);
        cc.setChargeCode(chargeCode.trim().toUpperCase());
        cc.setChargeName(chargeName);
        cc.setChargeCategory(chargeCategory != null && !chargeCategory.isBlank() ? chargeCategory : null);
        cc.setEventTrigger(eventTrigger);
        cc.setCalculationType(calculationType);
        cc.setFrequency(frequency != null && !frequency.isBlank() ? frequency : null);
        cc.setBaseAmount(baseAmount);
        cc.setPercentage(percentage);
        cc.setSlabJson(slabJson);
        cc.setMinAmount(minAmount);
        cc.setMaxAmount(maxAmount);
        cc.setCurrencyCode(currencyCode != null && !currencyCode.isBlank() ? currencyCode : "INR");
        cc.setGstApplicable(gstApplicable);
        cc.setGstRate(gstApplicable ? gstRate : null);
        cc.setGlChargeIncome(glChargeIncome);
        cc.setGlGstPayable(gstApplicable ? glGstPayable : null);
        cc.setWaiverAllowed(waiverAllowed);
        cc.setMaxWaiverPercent(waiverAllowed ? maxWaiverPercent : null);
        cc.setProductCode(productCode != null && !productCode.isBlank() ? productCode : null);
        cc.setChannel(channel != null && !channel.isBlank() ? channel : null);
        cc.setValidFrom(validFrom);
        cc.setValidTo(validTo);
        cc.setCustomerDescription(customerDescription != null && !customerDescription.isBlank() ? customerDescription : null);
        cc.setIsActive(true);
        cc.setCreatedBy(user);

        ChargeConfig saved = chargeConfigRepository.save(cc);

        auditService.logEvent(
                "ChargeConfig",
                saved.getId(),
                "CHARGE_CREATED",
                null,
                saved.getChargeCode(),
                "CHARGE_CONFIG",
                "Charge created: " + saved.getChargeCode()
                        + " | " + saved.getCalculationType()
                        + " | Trigger: " + saved.getEventTrigger()
                        + " | By: " + user);

        log.info("ChargeConfig created: id={}, code={}, calcType={}, trigger={}, by={}",
                saved.getId(), chargeCode, calculationType, eventTrigger, user);

        return saved;
    }

    @Override
    @Transactional
    public ChargeConfig updateChargeConfig(
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
            String customerDescription) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        ChargeConfig cc = chargeConfigRepository.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "CHARGE_NOT_FOUND",
                        "Charge config not found: " + id));

        // CBS Validation per Finacle CHRG_MASTER (same rules as createCharge)
        if ("FLAT".equals(calculationType) && (baseAmount == null || baseAmount.signum() <= 0)) {
            throw new BusinessException(
                    "BASE_AMOUNT_REQUIRED",
                    "Base amount is mandatory for FLAT charges.");
        }
        if ("PERCENTAGE".equals(calculationType) && (percentage == null || percentage.signum() <= 0)) {
            throw new BusinessException(
                    "PERCENTAGE_REQUIRED",
                    "Percentage is mandatory for PERCENTAGE charges.");
        }
        if ("SLAB".equals(calculationType) && (slabJson == null || slabJson.isBlank())) {
            throw new BusinessException(
                    "SLAB_JSON_REQUIRED",
                    "Slab JSON is mandatory for SLAB charges.");
        }
        if (gstApplicable && (gstRate == null || gstRate.signum() <= 0)) {
            throw new BusinessException(
                    "GST_RATE_REQUIRED",
                    "GST rate is mandatory when GST is applicable.");
        }

        String before = cc.getCalculationType() + "|" + cc.getBaseAmount() + "|" + cc.getPercentage();

        cc.setChargeName(chargeName);
        cc.setChargeCategory(chargeCategory != null && !chargeCategory.isBlank() ? chargeCategory : null);
        cc.setEventTrigger(eventTrigger);
        cc.setCalculationType(calculationType);
        cc.setFrequency(frequency != null && !frequency.isBlank() ? frequency : null);
        cc.setBaseAmount(baseAmount);
        cc.setPercentage(percentage);
        cc.setSlabJson(slabJson);
        cc.setMinAmount(minAmount);
        cc.setMaxAmount(maxAmount);
        cc.setCurrencyCode(currencyCode != null && !currencyCode.isBlank() ? currencyCode : "INR");
        cc.setGstApplicable(gstApplicable);
        cc.setGstRate(gstApplicable ? gstRate : null);
        cc.setGlChargeIncome(glChargeIncome);
        cc.setGlGstPayable(gstApplicable ? glGstPayable : null);
        cc.setWaiverAllowed(waiverAllowed);
        cc.setMaxWaiverPercent(waiverAllowed ? maxWaiverPercent : null);
        cc.setProductCode(productCode != null && !productCode.isBlank() ? productCode : null);
        cc.setChannel(channel != null && !channel.isBlank() ? channel : null);
        cc.setValidFrom(validFrom);
        cc.setValidTo(validTo);
        cc.setCustomerDescription(customerDescription != null && !customerDescription.isBlank() ? customerDescription : null);
        cc.setUpdatedBy(user);

        ChargeConfig saved = chargeConfigRepository.save(cc);

        String after = saved.getCalculationType() + "|" + saved.getBaseAmount() + "|" + saved.getPercentage();
        auditService.logEvent(
                "ChargeConfig",
                saved.getId(),
                "CHARGE_UPDATED",
                before,
                after,
                "CHARGE_CONFIG",
                "Charge updated: " + saved.getChargeCode() + " by " + user);

        log.info("ChargeConfig updated: id={}, code={}, by={}",
                saved.getId(), saved.getChargeCode(), user);

        return saved;
    }

    @Override
    @Transactional
    public ChargeConfig toggleChargeConfigActive(Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        ChargeConfig cc = chargeConfigRepository.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "CHARGE_NOT_FOUND",
                        "Charge config not found: " + id));

        boolean newState = !cc.getIsActive();
        cc.setIsActive(newState);
        cc.setUpdatedBy(user);

        ChargeConfig saved = chargeConfigRepository.save(cc);

        auditService.logEvent(
                "ChargeConfig",
                saved.getId(),
                newState ? "CHARGE_ACTIVATED" : "CHARGE_DEACTIVATED",
                String.valueOf(!newState),
                String.valueOf(newState),
                "CHARGE_CONFIG",
                "Charge " + (newState ? "activated" : "deactivated")
                        + ": " + saved.getChargeCode() + " by " + user);

        log.info("ChargeConfig toggled: id={}, active={}, code={}, by={}",
                saved.getId(), newState, saved.getChargeCode(), user);

        return saved;
    }
}