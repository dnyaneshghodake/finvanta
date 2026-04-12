package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Collateral;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.CollateralType;
import com.finvanta.repository.CollateralRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Collateral Management Service per Finacle COLMAS / Temenos AA.COLLATERAL.
 *
 * Manages the full collateral lifecycle:
 *   Register -> Valuate -> Create Lien -> Link to Loan -> Release on Closure
 *
 * LTV (Loan-to-Value) enforcement per RBI norms:
 *   - Gold: max 75% (RBI circular 24 Feb 2020)
 *   - Property: max 80% (RBI Housing Finance guidelines)
 *   - Vehicle: max 85%
 *   - FD: max 90%
 *
 * LTV is validated at:
 *   1. Collateral registration (warning if LTV exceeds limit)
 *   2. Loan approval (hard rejection if LTV exceeds limit)
 *   3. Periodic revaluation during EOD (alert if LTV breaches)
 */
@Service
public class CollateralService {

    private static final Logger log = LoggerFactory.getLogger(CollateralService.class);

    private final CollateralRepository collateralRepository;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerRepository customerRepository;
    private final AuditService auditService;
    private final CbsReferenceService cbsReferenceService;

    public CollateralService(
            CollateralRepository collateralRepository,
            LoanApplicationRepository applicationRepository,
            CustomerRepository customerRepository,
            AuditService auditService,
            CbsReferenceService cbsReferenceService) {
        this.collateralRepository = collateralRepository;
        this.applicationRepository = applicationRepository;
        this.customerRepository = customerRepository;
        this.auditService = auditService;
        this.cbsReferenceService = cbsReferenceService;
    }

    /**
     * Registers a new collateral against a loan application.
     * Validates collateral type-specific mandatory fields and calculates initial LTV.
     */
    @Transactional
    public Collateral registerCollateral(Collateral collateral, Long applicationId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanApplication app = applicationRepository
                .findById(applicationId)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() ->
                        new BusinessException("APPLICATION_NOT_FOUND", "Loan application not found: " + applicationId));

        // Validate type-specific mandatory fields
        validateCollateralFields(collateral);

        collateral.setTenantId(tenantId);
        collateral.setLoanApplication(app);
        collateral.setCustomer(app.getCustomer());
        collateral.setCollateralRef(cbsReferenceService.generateCollateralRef());
        collateral.setStatus("ACTIVE");
        collateral.setLienStatus("PENDING");
        collateral.setCreatedBy(currentUser);

        // Auto-calculate market value for gold based on weight and rate
        if (collateral.getCollateralType() == CollateralType.GOLD
                && collateral.getGoldNetWeightGrams() != null
                && collateral.getGoldRatePerGram() != null) {
            BigDecimal goldValue = collateral
                    .getGoldNetWeightGrams()
                    .multiply(collateral.getGoldRatePerGram())
                    .setScale(2, RoundingMode.HALF_UP);
            collateral.setMarketValue(goldValue);
            collateral.setValuationAmount(goldValue);
        }

        // Auto-set FD market value
        if (collateral.getCollateralType() == CollateralType.FD && collateral.getFdAmount() != null) {
            collateral.setMarketValue(collateral.getFdAmount());
            collateral.setValuationAmount(collateral.getFdAmount());
        }

        Collateral saved = collateralRepository.save(collateral);

        // LTV check (warning, not rejection -- rejection happens at approval)
        BigDecimal ltv = saved.calculateLtv(app.getRequestedAmount());
        BigDecimal maxLtv = saved.getCollateralType().getMaxLtvPercent();
        if (ltv != null && maxLtv != null && ltv.compareTo(maxLtv) > 0) {
            log.warn(
                    "LTV BREACH: collateral={}, ltv={}%, max={}%, loan={}",
                    saved.getCollateralRef(), ltv, maxLtv, app.getApplicationNumber());
        }

        auditService.logEvent(
                "Collateral",
                saved.getId(),
                "REGISTER",
                null,
                saved.getCollateralRef(),
                "COLLATERAL",
                "Collateral registered: type=" + saved.getCollateralType()
                        + ", marketValue=" + saved.getMarketValue()
                        + ", LTV=" + (ltv != null ? ltv + "%" : "N/A"));

        log.info(
                "Collateral registered: ref={}, type={}, value={}, app={}",
                saved.getCollateralRef(),
                saved.getCollateralType(),
                saved.getMarketValue(),
                app.getApplicationNumber());

        return saved;
    }

    /**
     * Validates LTV ratio for a loan application against all its collaterals.
     * Called at loan approval to enforce RBI LTV limits.
     *
     * @param applicationId Loan application ID
     * @param loanAmount    Loan amount to check against collateral value
     * @throws BusinessException if LTV exceeds the limit for any collateral type
     */
    public void validateLtv(Long applicationId, BigDecimal loanAmount) {
        String tenantId = TenantContext.getCurrentTenant();
        List<Collateral> collaterals = collateralRepository.findByTenantIdAndLoanApplicationId(tenantId, applicationId);

        if (collaterals.isEmpty()) {
            // No collaterals -- unsecured loan, no LTV check needed
            return;
        }

        BigDecimal totalMarketValue = collateralRepository.sumMarketValueByApplication(tenantId, applicationId);

        if (totalMarketValue.signum() <= 0) {
            throw new BusinessException(
                    "COLLATERAL_NO_VALUE", "Collateral has no market value. Valuation is required before approval.");
        }

        // Overall LTV across all collaterals
        BigDecimal overallLtv =
                loanAmount.multiply(new BigDecimal("100")).divide(totalMarketValue, 2, RoundingMode.HALF_UP);

        // Check per-collateral-type LTV limits
        for (Collateral c : collaterals) {
            BigDecimal maxLtv = c.getCollateralType().getMaxLtvPercent();
            if (maxLtv == null) continue; // UNSECURED type

            BigDecimal ltv = c.calculateLtv(loanAmount);
            if (ltv != null && ltv.compareTo(maxLtv) > 0) {
                throw new BusinessException(
                        "LTV_EXCEEDED",
                        "LTV ratio " + ltv + "% exceeds RBI maximum " + maxLtv
                                + "% for " + c.getCollateralType()
                                + " collateral " + c.getCollateralRef()
                                + ". Loan amount: INR " + loanAmount
                                + ", Collateral value: INR " + c.getMarketValue());
            }
        }

        log.info(
                "LTV validation passed: appId={}, loanAmount={}, collateralValue={}, overallLtv={}%",
                applicationId, loanAmount, totalMarketValue, overallLtv);
    }

    /**
     * Returns all collaterals for a loan application.
     */
    public List<Collateral> getCollaterals(Long applicationId) {
        return collateralRepository.findByTenantIdAndLoanApplicationId(TenantContext.getCurrentTenant(), applicationId);
    }

    /**
     * Releases all collateral liens for a loan account on closure/write-off.
     *
     * Per Finacle COLMAS / RBI Secured Lending Guidelines:
     * When a loan reaches terminal state (CLOSED, WRITTEN_OFF), all collateral liens
     * must be released. The customer has no further obligation and the bank must
     * release the pledge/mortgage within 30 days per RBI Fair Practices Code 2023.
     *
     * Called by LoanAccountServiceImpl when account transitions to terminal state.
     *
     * @param loanAccountId The loan account ID whose collaterals should be released
     * @param businessDate  CBS business date for audit trail
     */
    @Transactional
    public void releaseCollateralsForLoan(Long loanAccountId, java.time.LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        List<Collateral> collaterals = collateralRepository.findByTenantIdAndLoanAccountId(tenantId, loanAccountId);
        for (Collateral c : collaterals) {
            if ("RELEASED".equals(c.getLienStatus()) || "RELEASED".equals(c.getStatus())) {
                continue; // Already released
            }

            String previousLienStatus = c.getLienStatus();
            c.setLienStatus("RELEASED");
            c.setStatus("RELEASED");
            c.setUpdatedBy(currentUser);
            collateralRepository.save(c);

            auditService.logEvent(
                    "Collateral",
                    c.getId(),
                    "LIEN_RELEASED",
                    previousLienStatus,
                    "RELEASED",
                    "COLLATERAL",
                    "Collateral lien released on loan closure: " + c.getCollateralRef()
                            + " | Type: " + c.getCollateralType()
                            + " | Date: " + businessDate
                            + " | By: " + currentUser);

            log.info("Collateral lien released: ref={}, type={}, loanAccount={}",
                    c.getCollateralRef(), c.getCollateralType(), loanAccountId);
        }
    }

    /**
     * Validates type-specific mandatory fields.
     */
    private void validateCollateralFields(Collateral c) {
        if (c.getCollateralType() == null) {
            throw new BusinessException("COLLATERAL_TYPE_REQUIRED", "Collateral type is mandatory");
        }
        if (c.getOwnerName() == null || c.getOwnerName().isBlank()) {
            throw new BusinessException("COLLATERAL_OWNER_REQUIRED", "Collateral owner name is mandatory");
        }

        switch (c.getCollateralType()) {
            case GOLD -> {
                if (c.getGoldPurity() == null || c.getGoldPurity().isBlank()) {
                    throw new BusinessException(
                            "GOLD_PURITY_REQUIRED", "Gold purity (18K/22K/24K) is mandatory for gold collateral");
                }
                if (c.getGoldWeightGrams() == null || c.getGoldWeightGrams().signum() <= 0) {
                    throw new BusinessException("GOLD_WEIGHT_REQUIRED", "Gold weight in grams is mandatory");
                }
                if (c.getGoldRatePerGram() == null || c.getGoldRatePerGram().signum() <= 0) {
                    throw new BusinessException("GOLD_RATE_REQUIRED", "Gold rate per gram is mandatory for valuation");
                }
            }
            case PROPERTY -> {
                if (c.getPropertyAddress() == null || c.getPropertyAddress().isBlank()) {
                    throw new BusinessException(
                            "PROPERTY_ADDRESS_REQUIRED", "Property address is mandatory for property collateral");
                }
                if (c.getPropertyType() == null || c.getPropertyType().isBlank()) {
                    throw new BusinessException(
                            "PROPERTY_TYPE_REQUIRED", "Property type (RESIDENTIAL/COMMERCIAL/LAND) is mandatory");
                }
            }
            case VEHICLE -> {
                if (c.getVehicleRegistration() == null
                        || c.getVehicleRegistration().isBlank()) {
                    throw new BusinessException("VEHICLE_REG_REQUIRED", "Vehicle registration number is mandatory");
                }
            }
            case FD -> {
                if (c.getFdNumber() == null || c.getFdNumber().isBlank()) {
                    throw new BusinessException("FD_NUMBER_REQUIRED", "FD number is mandatory for FD collateral");
                }
                if (c.getFdAmount() == null || c.getFdAmount().signum() <= 0) {
                    throw new BusinessException("FD_AMOUNT_REQUIRED", "FD amount is mandatory");
                }
            }
            default -> {
                // SHARES, MACHINERY, INVENTORY, RECEIVABLES, UNSECURED
                // Market value is the primary validation for these
                if (c.getCollateralType() != CollateralType.UNSECURED
                        && (c.getMarketValue() == null || c.getMarketValue().signum() <= 0)) {
                    throw new BusinessException(
                            "COLLATERAL_VALUE_REQUIRED",
                            "Market value is mandatory for " + c.getCollateralType() + " collateral");
                }
            }
        }
    }
}
