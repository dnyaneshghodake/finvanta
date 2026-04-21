package com.finvanta.api;

import com.finvanta.compliance.AmlComplianceService;
import com.finvanta.compliance.CreditBureauService;
import com.finvanta.compliance.PslComplianceService;
import com.finvanta.domain.entity.CreditBureauInquiry;
import com.finvanta.domain.enums.PslCategory;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Regulatory Compliance REST API per Finacle COMPLIANCE_API / RBI OSMOS.
 *
 * <p>Exposes PSL, AML/CFT, and Credit Bureau operations for the BFF.
 * Per RBI IT Governance Direction 2023: compliance operations require
 * ADMIN or CHECKER role with full audit trail.
 *
 * <p>Endpoint groups:
 * <ul>
 *   <li>{@code /api/v1/compliance/psl/*} — Priority Sector Lending (GAP-02)</li>
 *   <li>{@code /api/v1/compliance/aml/*} — AML/CFT operations (GAP-03)</li>
 *   <li>{@code /api/v1/compliance/bureau/*} — Credit Bureau (GAP-05)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceApiController {

    private final PslComplianceService pslService;
    private final AmlComplianceService amlService;
    private final CreditBureauService bureauService;
    private final com.finvanta.compliance.CustomerCertificateService certificateService;

    public ComplianceApiController(
            PslComplianceService pslService,
            AmlComplianceService amlService,
            CreditBureauService bureauService,
            com.finvanta.compliance.CustomerCertificateService certificateService) {
        this.pslService = pslService;
        this.amlService = amlService;
        this.bureauService = bureauService;
        this.certificateService = certificateService;
    }

    // ========================================================================
    // PSL — Priority Sector Lending (per RBI PSL MD 2020)
    // ========================================================================

    /** Classify a loan under a PSL category (MAKER action). */
    @PostMapping("/psl/classify")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> classifyPsl(
            @RequestBody PslClassifyRequest req) {
        // CBS: Validate category string before valueOf() to avoid leaking
        // internal class names (IllegalArgumentException exposes
        // "No enum constant com.finvanta.domain.enums.PslCategory.INVALID").
        PslCategory category;
        try {
            category = PslCategory.valueOf(req.category());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new com.finvanta.util.BusinessException(
                    "INVALID_PSL_CATEGORY",
                    "Invalid PSL category: '" + req.category()
                            + "'. Valid values: " + java.util.Arrays.toString(PslCategory.values()));
        }
        pslService.classifyLoan(
                req.accountNumber(), category,
                req.subCategory(), req.weakerSection());
        return ResponseEntity.ok(ApiResponse.success(
                null, "PSL classified: " + req.accountNumber()));
    }

    /** Certify a PSL classification (CHECKER action in maker-checker). */
    @PostMapping("/psl/certify")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> certifyPsl(
            @RequestBody PslCertifyRequest req) {
        pslService.certifyPslClassification(req.accountNumber());
        return ResponseEntity.ok(ApiResponse.success(
                null, "PSL certified: " + req.accountNumber()));
    }

    /** PSL achievement vs RBI targets — dashboard data. */
    @GetMapping("/psl/achievement")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>>
            getPslAchievement() {
        Map<String, Object> data = pslService.computePslAchievement();
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ========================================================================
    // AML/CFT — Anti-Money Laundering (per PMLA 2002)
    // ========================================================================

    /** Create a Suspicious Transaction Report (STR). ADMIN only per PMLA §66. */
    @PostMapping("/aml/str")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> createStr(
            @RequestBody StrCreateRequest req) {
        String strRef = amlService.createStr(
                req.customerId(), req.accountReference(),
                req.category(), req.amount(),
                req.narrative(), req.detectionMethod(),
                req.ruleId(), req.riskScore());
        return ResponseEntity.ok(ApiResponse.success(
                strRef, "STR created: " + strRef));
    }

    /** Evaluate customer AML risk score. */
    @GetMapping("/aml/risk/{customerId}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Integer>> getAmlRisk(
            @PathVariable Long customerId) {
        int score = amlService.evaluateCustomerRisk(customerId);
        return ResponseEntity.ok(ApiResponse.success(score));
    }

    // ========================================================================
    // Credit Bureau (per CICRA 2005)
    // ========================================================================

    /** Initiate a credit bureau inquiry. */
    @PostMapping("/bureau/inquiry")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<BureauInquiryResponse>>
            initiateBureauInquiry(@RequestBody BureauInquiryRequest req) {
        CreditBureauInquiry inquiry = bureauService.initiateInquiry(
                req.customerId(), req.applicationId(),
                req.bureauName() != null ? req.bureauName()
                        : CreditBureauService.DEFAULT_BUREAU,
                req.purpose() != null ? req.purpose()
                        : "LOAN_ORIGINATION");
        return ResponseEntity.ok(ApiResponse.success(
                BureauInquiryResponse.from(inquiry)));
    }

    /** Get latest credit score for a customer. */
    @GetMapping("/bureau/score/{customerId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Integer>> getLatestScore(
            @PathVariable Long customerId) {
        Integer score = bureauService.getLatestScore(customerId);
        return ResponseEntity.ok(ApiResponse.success(score));
    }

    /** Get credit bureau inquiry history for a customer. */
    @GetMapping("/bureau/history/{customerId}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<BureauInquiryResponse>>>
            getBureauHistory(@PathVariable Long customerId) {
        List<BureauInquiryResponse> history = bureauService
                .getInquiryHistory(customerId).stream()
                .map(BureauInquiryResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    // ========================================================================
    // Customer Certificates (per RBI Fair Practices Code / IT Act)
    // ========================================================================

    /** Interest Certificate (Form 16A equivalent) per IT Act Section 203. */
    @GetMapping("/certificates/interest/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>>
            getInterestCertificate(
                    @PathVariable String accountNumber,
                    @RequestParam(defaultValue = "2024-25") String financialYear) {
        Map<String, Object> data = certificateService
                .generateInterestCertificateData(accountNumber, financialYear);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** Loan Closure NOC per RBI Fair Practices Code 2023. */
    @GetMapping("/certificates/noc/{loanAccountNumber}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>>
            getLoanClosureNoc(@PathVariable String loanAccountNumber) {
        Map<String, Object> data = certificateService
                .generateLoanClosureNocData(loanAccountNumber);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** Balance Confirmation Certificate for auditors. */
    @GetMapping("/certificates/balance/{accountNumber}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>>
            getBalanceConfirmation(
                    @PathVariable String accountNumber,
                    @RequestParam(required = false) String asOfDate) {
        java.time.LocalDate date = asOfDate != null
                ? java.time.LocalDate.parse(asOfDate)
                : java.time.LocalDate.now();
        Map<String, Object> data = certificateService
                .generateBalanceConfirmationData(accountNumber, date);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ========================================================================
    // Request / Response DTOs
    // ========================================================================

    public record PslClassifyRequest(
            String accountNumber,
            String category,
            String subCategory,
            boolean weakerSection) {}

    public record PslCertifyRequest(String accountNumber) {}

    public record StrCreateRequest(
            Long customerId,
            String accountReference,
            String category,
            java.math.BigDecimal amount,
            String narrative,
            String detectionMethod,
            String ruleId,
            int riskScore) {}

    public record BureauInquiryRequest(
            Long customerId,
            Long applicationId,
            String bureauName,
            String purpose) {}

    public record BureauInquiryResponse(
            String inquiryReference,
            String bureauName,
            String inquiryDate,
            Integer creditScore,
            String status,
            Integer dpdMax12m,
            Integer overdueAccounts) {

        static BureauInquiryResponse from(CreditBureauInquiry i) {
            return new BureauInquiryResponse(
                    i.getInquiryReference(),
                    i.getBureauName(),
                    i.getInquiryDate() != null
                            ? i.getInquiryDate().toString() : null,
                    i.getCreditScore(),
                    i.getStatus(),
                    i.getDpdMaxLast12m(),
                    i.getOverdueAccounts());
        }
    }
}
