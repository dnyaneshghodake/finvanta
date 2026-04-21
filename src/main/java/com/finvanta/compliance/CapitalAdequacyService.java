package com.finvanta.compliance;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.Tenant;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.TenantRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Basel III Capital Adequacy Service per Finacle CAPITAL_ADEQUACY / Temenos EB.CAPITAL.
 *
 * <p>Per RBI Master Circular on Basel III Capital Regulations (2023):
 * <ul>
 *   <li><b>Minimum CRAR:</b> 9% (vs Basel III global minimum 8%)</li>
 *   <li><b>Minimum CET1:</b> 5.5% of RWA</li>
 *   <li><b>Capital Conservation Buffer (CCB):</b> 2.5% of RWA</li>
 *   <li><b>Total with CCB:</b> 11.5% of RWA</li>
 * </ul>
 *
 * <p><b>Risk Weight Assignment (Standardized Approach):</b>
 * <ul>
 *   <li>Cash, Gold, G-Secs: 0%</li>
 *   <li>Claims on domestic banks: 20%</li>
 *   <li>Residential mortgage (LTV ≤ 80%): 35%</li>
 *   <li>Retail (non-housing): 75%</li>
 *   <li>Commercial real estate: 100%</li>
 *   <li>NPA (net of provisions): 50-150% based on provision coverage</li>
 *   <li>Off-balance sheet: Credit Conversion Factor × risk weight</li>
 * </ul>
 *
 * <p><b>Phase 1:</b> Computes CRAR from loan portfolio using simplified risk weights.
 * Tier-1 capital is read from Tenant configuration (updated quarterly by admin).
 * Phase 2: Full RWA computation with off-balance sheet, market risk, operational risk.
 *
 * <p><b>PCA Trigger Levels per RBI Framework:</b>
 * <ul>
 *   <li>Risk Threshold 1: CRAR &lt; 10.25% but ≥ 7.75%</li>
 *   <li>Risk Threshold 2: CRAR &lt; 7.75% but ≥ 6.25%</li>
 *   <li>Risk Threshold 3: CRAR &lt; 6.25%</li>
 * </ul>
 *
 * @see com.finvanta.domain.entity.Tenant#getTier1CapitalBase()
 */
@Service
public class CapitalAdequacyService {

    private static final Logger log = LoggerFactory.getLogger(CapitalAdequacyService.class);

    /** RBI minimum CRAR for Indian banks (higher than Basel III global 8%) */
    private static final BigDecimal MIN_CRAR = new BigDecimal("9.00");

    /** RBI minimum CET1 ratio */
    private static final BigDecimal MIN_CET1 = new BigDecimal("5.50");

    /** Capital Conservation Buffer */
    private static final BigDecimal CCB = new BigDecimal("2.50");

    /** Total minimum with CCB */
    private static final BigDecimal MIN_CRAR_WITH_CCB = new BigDecimal("11.50");

    // --- Standardized Risk Weights per RBI Basel III Circular ---
    private static final BigDecimal RW_HOUSING_LOW_LTV = new BigDecimal("0.35");
    private static final BigDecimal RW_RETAIL = new BigDecimal("0.75");
    private static final BigDecimal RW_COMMERCIAL = new BigDecimal("1.00");
    private static final BigDecimal RW_NPA_LOW_PROVISION = new BigDecimal("1.50");
    private static final BigDecimal RW_NPA_HIGH_PROVISION = new BigDecimal("0.50");

    /** NPA provision coverage threshold: above 50% gets lower risk weight */
    private static final BigDecimal NPA_PROVISION_THRESHOLD = new BigDecimal("0.50");

    // --- PCA Trigger Levels per RBI PCA Framework ---
    private static final BigDecimal PCA_THRESHOLD_1 = new BigDecimal("10.25");
    private static final BigDecimal PCA_THRESHOLD_2 = new BigDecimal("7.75");
    private static final BigDecimal PCA_THRESHOLD_3 = new BigDecimal("6.25");

    private final LoanAccountRepository loanAccountRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final TenantRepository tenantRepository;

    public CapitalAdequacyService(
            LoanAccountRepository loanAccountRepository,
            DepositAccountRepository depositAccountRepository,
            TenantRepository tenantRepository) {
        this.loanAccountRepository = loanAccountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Computes Capital to Risk-Weighted Assets Ratio (CRAR).
     *
     * <p>CRAR = (Tier-1 Capital + Tier-2 Capital) / Risk-Weighted Assets × 100
     *
     * <p>Phase 1 simplifications:
     * <ul>
     *   <li>Tier-1 capital from Tenant.tier1CapitalBase (admin-maintained)</li>
     *   <li>Tier-2 capital = 0 (no subordinated debt module yet)</li>
     *   <li>RWA = credit risk only (no market risk, no operational risk)</li>
     *   <li>Off-balance sheet items not included</li>
     * </ul>
     *
     * @return Map with CRAR computation details
     */
    @Transactional(readOnly = true)
    public Map<String, Object> computeCrar() {
        String tenantId = TenantContext.getCurrentTenant();
        Tenant tenant = tenantRepository.findByTenantCode(tenantId).orElse(null);

        BigDecimal tier1Capital = tenant != null && tenant.getTier1CapitalBase() != null
                ? tenant.getTier1CapitalBase() : BigDecimal.ZERO;
        BigDecimal tier2Capital = BigDecimal.ZERO; // Phase 2: subordinated debt

        BigDecimal totalCapital = tier1Capital.add(tier2Capital);

        // Compute Risk-Weighted Assets from loan portfolio
        List<LoanAccount> allLoans = loanAccountRepository.findAllActiveAccounts(tenantId);

        BigDecimal totalRwa = BigDecimal.ZERO;
        BigDecimal housingRwa = BigDecimal.ZERO;
        BigDecimal retailRwa = BigDecimal.ZERO;
        BigDecimal commercialRwa = BigDecimal.ZERO;
        BigDecimal npaRwa = BigDecimal.ZERO;

        for (LoanAccount loan : allLoans) {
            BigDecimal outstanding = loan.getOutstandingPrincipal() != null
                    ? loan.getOutstandingPrincipal() : BigDecimal.ZERO;
            BigDecimal riskWeight;

            if (loan.getStatus().isNpa()) {
                // NPA: risk weight depends on provision coverage
                // CBS: Null-safe provisioningAmount — JPA sets null for DB rows where
                // the column is NULL (Java field default BigDecimal.ZERO only applies
                // to objects created in Java, not loaded from DB).
                BigDecimal provisionAmt = loan.getProvisioningAmount() != null
                        ? loan.getProvisioningAmount() : BigDecimal.ZERO;
                BigDecimal provisionCoverage = outstanding.signum() > 0
                        ? provisionAmt.divide(outstanding, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                riskWeight = provisionCoverage.compareTo(NPA_PROVISION_THRESHOLD) >= 0
                        ? RW_NPA_HIGH_PROVISION : RW_NPA_LOW_PROVISION;
                BigDecimal netExposure = outstanding.subtract(provisionAmt).max(BigDecimal.ZERO);
                BigDecimal rwa = netExposure.multiply(riskWeight);
                npaRwa = npaRwa.add(rwa);
                totalRwa = totalRwa.add(rwa);
            } else if ("HOUSING".equals(loan.getPslCategory())
                    || "HOUSING".equals(loan.getSectoralClassification())) {
                BigDecimal rwa = outstanding.multiply(RW_HOUSING_LOW_LTV);
                housingRwa = housingRwa.add(rwa);
                totalRwa = totalRwa.add(rwa);
            } else if ("RETAIL".equals(loan.getSectoralClassification())
                    || "PERSONAL".equals(loan.getSectoralClassification())
                    || "EDUCATION".equals(loan.getPslCategory())) {
                BigDecimal rwa = outstanding.multiply(RW_RETAIL);
                retailRwa = retailRwa.add(rwa);
                totalRwa = totalRwa.add(rwa);
            } else {
                BigDecimal rwa = outstanding.multiply(RW_COMMERCIAL);
                commercialRwa = commercialRwa.add(rwa);
                totalRwa = totalRwa.add(rwa);
            }
        }

        // CRAR = Total Capital / RWA × 100
        // CBS CRITICAL: When RWA is zero (no loan exposure), CRAR is not meaningful.
        // Per Basel III: a bank with zero RWA and positive capital is fully compliant —
        // there is no risk to absorb. Setting CRAR to zero would incorrectly trigger
        // PCA_THRESHOLD_3 (the most severe Prompt Corrective Action level).
        // A sentinel value of 999.99 signals "no risk exposure" to the BFF/dashboard.
        BigDecimal crar;
        BigDecimal tier1Ratio;
        boolean zeroRwa = totalRwa.signum() == 0;
        if (zeroRwa) {
            crar = totalCapital.signum() > 0 ? new BigDecimal("999.99") : BigDecimal.ZERO;
            tier1Ratio = crar;
        } else {
            crar = totalCapital.multiply(BigDecimal.valueOf(100))
                    .divide(totalRwa, 2, RoundingMode.HALF_UP);
            tier1Ratio = tier1Capital.multiply(BigDecimal.valueOf(100))
                    .divide(totalRwa, 2, RoundingMode.HALF_UP);
        }

        // PCA assessment — skip when RWA is zero (no exposure = no PCA trigger)
        String pcaStatus = "NORMAL";
        if (!zeroRwa) {
            if (crar.compareTo(PCA_THRESHOLD_3) < 0) {
                pcaStatus = "PCA_THRESHOLD_3";
            } else if (crar.compareTo(PCA_THRESHOLD_2) < 0) {
                pcaStatus = "PCA_THRESHOLD_2";
            } else if (crar.compareTo(PCA_THRESHOLD_1) < 0) {
                pcaStatus = "PCA_THRESHOLD_1";
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // Capital
        result.put("tier1Capital", tier1Capital);
        result.put("tier2Capital", tier2Capital);
        result.put("totalCapital", totalCapital);

        // RWA breakdown
        result.put("totalRwa", totalRwa);
        result.put("housingRwa", housingRwa);
        result.put("retailRwa", retailRwa);
        result.put("commercialRwa", commercialRwa);
        result.put("npaRwa", npaRwa);

        // Ratios
        result.put("crar", crar);
        result.put("tier1Ratio", tier1Ratio);
        result.put("minimumCrar", MIN_CRAR);
        result.put("minimumCet1", MIN_CET1);
        result.put("ccb", CCB);
        result.put("minimumWithCcb", MIN_CRAR_WITH_CCB);
        result.put("crarCompliant", crar.compareTo(MIN_CRAR) >= 0);
        result.put("crarWithCcbCompliant", crar.compareTo(MIN_CRAR_WITH_CCB) >= 0);

        // PCA status
        result.put("pcaStatus", pcaStatus);
        result.put("pcaTriggered", !"NORMAL".equals(pcaStatus));

        log.info("CRAR computed: tier1={}, rwa={}, crar={}%, pca={}",
                tier1Capital, totalRwa, crar, pcaStatus);

        return result;
    }
}
