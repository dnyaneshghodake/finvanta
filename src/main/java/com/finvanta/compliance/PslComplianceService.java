package com.finvanta.compliance;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.PslCategory;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Priority Sector Lending Compliance Service per Finacle PSL_MASTER / Temenos AA.PSL.
 *
 * <p>Per RBI Master Direction on Priority Sector Lending (PSL) 2020:
 * Domestic scheduled commercial banks must achieve:
 * <ul>
 *   <li><b>Total PSL:</b> 40% of Adjusted Net Bank Credit (ANBC)</li>
 *   <li><b>Agriculture:</b> 18% of ANBC (of which 10% direct agriculture)</li>
 *   <li><b>Micro Enterprises:</b> 7.5% of ANBC</li>
 *   <li><b>Weaker Sections:</b> 12% of ANBC (cross-cutting)</li>
 * </ul>
 *
 * <p>Shortfall in PSL targets results in mandatory deposits to RIDF/MSME Fund/MUDRA
 * at below-market rates (Bank Rate minus 2%).
 *
 * <p><b>PSL Classification Workflow:</b>
 * <pre>
 *   Loan Origination → MAKER classifies PSL category → CHECKER certifies
 *   → psl_certified = true → Counted in PSL target computation
 * </pre>
 *
 * <p><b>Quarterly PSL Return:</b> Computed from certified loan accounts and
 * submitted to RBI via OSMOS. This service provides the data layer.
 *
 * @see PslCategory
 */
@Service
public class PslComplianceService {

    private static final Logger log = LoggerFactory.getLogger(PslComplianceService.class);

    /** RBI PSL target: 40% of ANBC for domestic scheduled commercial banks */
    private static final BigDecimal PSL_TOTAL_TARGET_PCT = new BigDecimal("40.00");

    /** RBI PSL sub-target: 18% of ANBC for Agriculture */
    private static final BigDecimal PSL_AGRICULTURE_TARGET_PCT = new BigDecimal("18.00");

    /** RBI PSL sub-target: 7.5% of ANBC for Micro Enterprises */
    private static final BigDecimal PSL_MICRO_TARGET_PCT = new BigDecimal("7.50");

    /** RBI PSL sub-target: 12% of ANBC for Weaker Sections (cross-cutting) */
    private static final BigDecimal PSL_WEAKER_TARGET_PCT = new BigDecimal("12.00");

    private final LoanAccountRepository loanAccountRepository;
    private final AuditService auditService;
    private final BusinessDateService businessDateService;

    public PslComplianceService(
            LoanAccountRepository loanAccountRepository,
            AuditService auditService,
            BusinessDateService businessDateService) {
        this.loanAccountRepository = loanAccountRepository;
        this.auditService = auditService;
        this.businessDateService = businessDateService;
    }

    /**
     * Classifies a loan account under a PSL category.
     *
     * <p>Per RBI PSL MD: Classification must be done at origination and
     * goes through maker-checker. Only certified classifications count
     * toward PSL target computation.
     *
     * @param accountNumber  Loan account number
     * @param category       PSL category
     * @param subCategory    PSL sub-category (nullable)
     * @param weakerSection  Whether borrower qualifies as weaker section
     */
    @Transactional
    public void classifyLoan(
            String accountNumber,
            PslCategory category,
            String subCategory,
            boolean weakerSection) {

        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = loanAccountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        String oldCategory = account.getPslCategory();
        account.setPslCategory(category.name());
        account.setPslSubCategory(subCategory);
        account.setWeakerSection(weakerSection);
        account.setPslCertified(false); // Requires checker certification
        account.setPslClassifiedBy(currentUser); // Dedicated field for maker-checker guard
        account.setUpdatedBy(currentUser);

        loanAccountRepository.save(account);

        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "PSL_CLASSIFY",
                oldCategory,
                category.name(),
                "PSL_COMPLIANCE",
                "PSL classified: " + accountNumber
                        + " | Category: " + category
                        + " | SubCategory: " + subCategory
                        + " | WeakerSection: " + weakerSection
                        + " | By: " + currentUser);

        log.info("PSL classified: accNo={}, category={}, subCategory={}, weakerSection={}",
                accountNumber, category, subCategory, weakerSection);
    }

    /**
     * Certifies a PSL classification (checker action in maker-checker workflow).
     *
     * <p>Per RBI: Only certified PSL classifications count toward target computation.
     * This prevents inflated PSL numbers from misclassification.
     */
    @Transactional
    public void certifyPslClassification(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = loanAccountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getPslCategory() == null) {
            throw new BusinessException("PSL_NOT_CLASSIFIED",
                    "Account " + accountNumber + " has no PSL classification to certify");
        }
        if (account.isPslCertified()) {
            throw new BusinessException("PSL_ALREADY_CERTIFIED",
                    "Account " + accountNumber + " PSL classification is already certified");
        }

        // CBS Maker-Checker: Self-approval guard per RBI Internal Controls.
        // The same user who classified (MAKER) cannot certify (CHECKER).
        // Per Finacle APPR_MASTER / Temenos OFS.AUTHORIZATION: dual control
        // prevents a single user from inflating PSL achievement numbers.
        //
        // CRITICAL: Uses dedicated pslClassifiedBy field, NOT the generic updatedBy.
        // updatedBy is overwritten by ANY mutation (EOD batch sets it to "SYSTEM",
        // interest accrual, NPA classification, etc.). If we compared updatedBy,
        // the original MAKER could self-certify after any intervening batch update.
        // CBS CRITICAL: pslClassifiedBy MUST be non-null for certification.
        // If null (pre-migration loan, batch import, or admin DB override), the
        // maker-checker trail is incomplete — block certification until a MAKER
        // explicitly classifies via classifyLoan(), which sets pslClassifiedBy.
        // Without this guard, a single user could self-certify any loan with
        // null pslClassifiedBy, defeating the dual-control requirement.
        String classifiedBy = account.getPslClassifiedBy();
        if (classifiedBy == null) {
            throw new BusinessException("PSL_CLASSIFIER_UNKNOWN",
                    "PSL classification for " + accountNumber + " has no recorded classifier. "
                            + "Re-classify via the PSL Classification screen before certifying.");
        }
        if (classifiedBy.equals(currentUser)) {
            throw new BusinessException("WORKFLOW_SELF_APPROVAL",
                    "PSL certification requires a different user than the one who classified. "
                            + "Classified by: " + classifiedBy + ", current user: " + currentUser);
        }

        account.setPslCertified(true);
        // CBS: Use CBS business date for regulatory dates, not LocalDate.now().
        // PSL certification date is audited in RBI OSMOS quarterly returns.
        LocalDate certDate;
        try {
            certDate = businessDateService.getCurrentBusinessDate();
        } catch (Exception e) {
            certDate = LocalDate.now();
        }
        account.setPslCertifiedDate(certDate);
        account.setUpdatedBy(currentUser);
        loanAccountRepository.save(account);

        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "PSL_CERTIFY",
                null,
                account.getPslCategory(),
                "PSL_COMPLIANCE",
                "PSL certified: " + accountNumber
                        + " | Category: " + account.getPslCategory()
                        + " | Certified by: " + currentUser);

        log.info("PSL certified: accNo={}, category={}, certifiedBy={}",
                accountNumber, account.getPslCategory(), currentUser);
    }

    /**
     * Computes PSL achievement vs targets for the current tenant.
     *
     * <p>Per RBI PSL MD: Achievement is measured as a percentage of ANBC.
     * This method returns a map with category-wise outstanding amounts
     * and achievement percentages.
     *
     * <p>Note: ANBC (Adjusted Net Bank Credit) must be provided externally
     * as it includes off-balance-sheet items not tracked in the loan module.
     * For Phase 1, we use total outstanding as a proxy.
     *
     * @return Map with PSL achievement data per category
     */
    @Transactional(readOnly = true)
    public Map<String, Object> computePslAchievement() {
        String tenantId = TenantContext.getCurrentTenant();
        List<LoanAccount> allActive = loanAccountRepository.findAllActiveAccounts(tenantId);

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal pslOutstanding = BigDecimal.ZERO;
        BigDecimal agricultureOutstanding = BigDecimal.ZERO;
        BigDecimal microOutstanding = BigDecimal.ZERO;
        BigDecimal weakerSectionOutstanding = BigDecimal.ZERO;

        for (LoanAccount account : allActive) {
            BigDecimal outstanding = account.getOutstandingPrincipal() != null
                    ? account.getOutstandingPrincipal() : BigDecimal.ZERO;
            totalOutstanding = totalOutstanding.add(outstanding);

            if (account.isPslCertified() && account.isPslClassified()) {
                pslOutstanding = pslOutstanding.add(outstanding);

                if ("AGRICULTURE".equals(account.getPslCategory())) {
                    agricultureOutstanding = agricultureOutstanding.add(outstanding);
                }
                if ("MICRO_ENTERPRISE".equals(account.getPslCategory())) {
                    microOutstanding = microOutstanding.add(outstanding);
                }
                if (account.isWeakerSection()) {
                    weakerSectionOutstanding = weakerSectionOutstanding.add(outstanding);
                }
            }
        }

        // Use total outstanding as ANBC proxy (Phase 1)
        BigDecimal anbc = totalOutstanding;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anbc", anbc);
        result.put("totalPslOutstanding", pslOutstanding);
        result.put("totalPslPct", pctOf(pslOutstanding, anbc));
        result.put("totalPslTarget", PSL_TOTAL_TARGET_PCT);
        result.put("totalPslShortfall", PSL_TOTAL_TARGET_PCT.subtract(pctOf(pslOutstanding, anbc)).max(BigDecimal.ZERO));

        result.put("agricultureOutstanding", agricultureOutstanding);
        result.put("agriculturePct", pctOf(agricultureOutstanding, anbc));
        result.put("agricultureTarget", PSL_AGRICULTURE_TARGET_PCT);

        result.put("microOutstanding", microOutstanding);
        result.put("microPct", pctOf(microOutstanding, anbc));
        result.put("microTarget", PSL_MICRO_TARGET_PCT);

        result.put("weakerSectionOutstanding", weakerSectionOutstanding);
        result.put("weakerSectionPct", pctOf(weakerSectionOutstanding, anbc));
        result.put("weakerSectionTarget", PSL_WEAKER_TARGET_PCT);

        return result;
    }

    private BigDecimal pctOf(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }
}
