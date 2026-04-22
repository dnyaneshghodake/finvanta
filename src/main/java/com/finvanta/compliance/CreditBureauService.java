package com.finvanta.compliance;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.CreditBureauInquiry;
import com.finvanta.repository.CreditBureauInquiryRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Credit Bureau Integration Service per Finacle BUREAU_INQUIRY / Temenos CR.BUREAU.CHECK.
 *
 * <p>Per Credit Information Companies (Regulation) Act 2005:
 * <ul>
 *   <li>Credit bureau check is <b>mandatory</b> before sanctioning any credit facility</li>
 *   <li>Monthly data submission to all 4 bureaus (CIBIL, Experian, Equifax, CRIF)</li>
 *   <li>Customer has the right to dispute incorrect information</li>
 * </ul>
 *
 * <p><b>Inquiry Flow:</b>
 * <pre>
 *   Loan Application → CreditBureauService.initiateInquiry()
 *     → Creates PENDING inquiry record
 *     → Calls bureau API (Phase 2: actual HTTP integration)
 *     → Updates with score, DPD, exposure data
 *     → Returns CreditBureauInquiry for decisioning
 * </pre>
 *
 * <p><b>Phase 1:</b> Records inquiry lifecycle and provides decisioning helpers.
 * Actual bureau API integration (CIBIL Connect, Experian PowerCurve) requires
 * bureau-specific credentials and will be implemented in Phase 2.
 *
 * <p><b>Duplicate Prevention:</b> If a successful inquiry exists within the last
 * 30 days for the same customer+bureau, a new inquiry is not initiated.
 * Per RBI: each inquiry impacts the customer's credit score, so unnecessary
 * inquiries must be avoided.
 *
 * @see CreditBureauInquiry
 */
@Service
public class CreditBureauService {

    private static final Logger log = LoggerFactory.getLogger(CreditBureauService.class);

    /** Default bureau for Indian banks — CIBIL (TransUnion) */
    public static final String DEFAULT_BUREAU = "CIBIL";

    /** Minimum days between duplicate inquiries to same bureau */
    private static final int DUPLICATE_CHECK_DAYS = 30;

    /** Minimum CIBIL score for standard lending (configurable per product in Phase 2) */
    public static final int DEFAULT_MIN_SCORE = 650;

    private final CreditBureauInquiryRepository inquiryRepository;
    private final AuditService auditService;

    public CreditBureauService(
            CreditBureauInquiryRepository inquiryRepository,
            AuditService auditService) {
        this.inquiryRepository = inquiryRepository;
        this.auditService = auditService;
    }

    /**
     * Initiates a credit bureau inquiry for a customer.
     *
     * <p>Per CICRA 2005: mandatory before any credit facility sanction.
     * If a recent successful inquiry exists (within 30 days), returns
     * the existing result to avoid unnecessary score impact.
     *
     * @param customerId    Customer to check
     * @param applicationId Loan application (nullable for review inquiries)
     * @param bureauName    Bureau to query (CIBIL, EXPERIAN, EQUIFAX, CRIF)
     * @param purpose       LOAN_ORIGINATION, REVIEW, RENEWAL, MONITORING
     * @return The inquiry record (with score if successful)
     */
    @Transactional
    public CreditBureauInquiry initiateInquiry(
            Long customerId,
            Long applicationId,
            String bureauName,
            String purpose) {

        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // Check for recent duplicate inquiry
        LocalDateTime sinceDate = LocalDateTime.now().minusDays(DUPLICATE_CHECK_DAYS);
        if (inquiryRepository.existsRecentInquiry(tenantId, customerId, bureauName, sinceDate)) {
            List<CreditBureauInquiry> recent = inquiryRepository.findLatestSuccessful(
                    tenantId, customerId, bureauName, PageRequest.of(0, 1));
            if (!recent.isEmpty()) {
                log.info("Recent bureau inquiry exists — returning cached result: customer={}, bureau={}, score={}",
                        customerId, bureauName, recent.get(0).getCreditScore());
                return recent.get(0);
            }
        }

        // Create new inquiry record
        CreditBureauInquiry inquiry = new CreditBureauInquiry();
        inquiry.setTenantId(tenantId);
        inquiry.setInquiryReference("CBI/" + ReferenceGenerator.generateTransactionRef());
        inquiry.setCustomerId(customerId);
        inquiry.setApplicationId(applicationId);
        inquiry.setBureauName(bureauName);
        inquiry.setInquiryDate(LocalDateTime.now());
        inquiry.setInquiryPurpose(purpose);
        inquiry.setStatus("PENDING");
        inquiry.setCreatedBy(currentUser);

        CreditBureauInquiry saved = inquiryRepository.save(inquiry);

        auditService.logEvent(
                "CreditBureauInquiry",
                saved.getId(),
                "INQUIRY_INITIATED",
                null,
                saved.getInquiryReference(),
                "COMPLIANCE",
                "Bureau inquiry initiated: customer=" + customerId
                        + ", bureau=" + bureauName
                        + ", purpose=" + purpose
                        + ", by=" + currentUser);

        log.info("Bureau inquiry initiated: ref={}, customer={}, bureau={}, purpose={}",
                saved.getInquiryReference(), customerId, bureauName, purpose);

        // Phase 2: Call bureau API here and update inquiry with response.
        // For Phase 1, the inquiry remains in PENDING status.
        // Actual integration requires bureau-specific API credentials.

        return saved;
    }

    /**
     * Records the bureau response for a pending inquiry.
     *
     * <p>Called by the bureau API callback handler (Phase 2) or manually
     * by the compliance officer after offline bureau check.
     */
    @Transactional
    public CreditBureauInquiry recordResponse(
            String inquiryReference,
            int creditScore,
            String scoreVersion,
            String reportData,
            Integer dpdMax12m,
            Integer dpdMax24m,
            Integer activeAccounts,
            Integer overdueAccounts) {

        String tenantId = TenantContext.getCurrentTenant();

        CreditBureauInquiry inquiry = inquiryRepository
                .findByTenantIdAndInquiryReference(tenantId, inquiryReference)
                .orElseThrow(() -> new BusinessException(
                        "INQUIRY_NOT_FOUND",
                        "Credit bureau inquiry not found: " + inquiryReference));

        inquiry.setCreditScore(creditScore);
        inquiry.setScoreVersion(scoreVersion);
        inquiry.setReportData(reportData);
        inquiry.setDpdMaxLast12m(dpdMax12m);
        inquiry.setDpdMaxLast24m(dpdMax24m);
        inquiry.setActiveAccounts(activeAccounts);
        inquiry.setOverdueAccounts(overdueAccounts);
        inquiry.setResponseCode("SUCCESS");
        inquiry.setStatus("SUCCESS");

        CreditBureauInquiry saved = inquiryRepository.save(inquiry);

        log.info("Bureau response recorded: ref={}, score={}, dpd12m={}, overdue={}",
                inquiryReference, creditScore, dpdMax12m, overdueAccounts);

        return saved;
    }

    /**
     * Returns the latest credit score for a customer from the default bureau.
     *
     * @return Credit score, or null if no successful inquiry exists
     */
    @Transactional(readOnly = true)
    public Integer getLatestScore(Long customerId) {
        String tenantId = TenantContext.getCurrentTenant();
        List<CreditBureauInquiry> results = inquiryRepository.findLatestSuccessful(
                tenantId, customerId, DEFAULT_BUREAU, PageRequest.of(0, 1));
        return results.isEmpty() ? null : results.get(0).getCreditScore();
    }

    /**
     * Returns the full inquiry history for a customer.
     */
    @Transactional(readOnly = true)
    public List<CreditBureauInquiry> getInquiryHistory(Long customerId) {
        String tenantId = TenantContext.getCurrentTenant();
        return inquiryRepository.findByTenantIdAndCustomerIdOrderByInquiryDateDesc(
                tenantId, customerId);
    }
}
