package com.finvanta.api;

import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.service.LoanApplicationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Loan Application REST API per Finacle LOAN_ORIG / Temenos AA.ARRANGEMENT.
 *
 * Thin orchestration layer over LoanApplicationService.
 * Per RBI Digital Lending Guidelines 2022 / Fair Practices Code.
 *
 * CBS Role Matrix:
 *   MAKER   → submit application
 *   CHECKER → verify, approve, reject
 *   ADMIN   → all MAKER + CHECKER operations
 *
 * Maker-Checker Enforcement:
 *   Per RBI: the maker (submitter) CANNOT verify/approve their own application.
 *   This is enforced in LoanApplicationServiceImpl (not in the controller).
 */
@RestController
@RequestMapping("/v1/loan-applications")
public class LoanApplicationController {

    private final LoanApplicationService appService;

    public LoanApplicationController(
            LoanApplicationService appService) {
        this.appService = appService;
    }

    // === Application Lifecycle ===

    /** Submit a new loan application. MAKER/ADMIN. */
    @PostMapping
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApplicationResponse>>
            submitApplication(
                    @Valid @RequestBody
                    SubmitApplicationRequest req) {
        LoanApplication app = new LoanApplication();
        app.setProductType(req.productType());
        app.setRequestedAmount(req.requestedAmount());
        app.setInterestRate(req.interestRate());
        app.setTenureMonths(req.tenureMonths());
        app.setPurpose(req.purpose());
        app.setCollateralReference(req.collateralReference());
        app.setDisbursementAccountNumber(
                req.disbursementAccountNumber());
        if (req.penalRate() != null) {
            app.setPenalRate(req.penalRate());
        }

        LoanApplication saved = appService
                .createApplication(
                        app, req.customerId(),
                        req.branchId());

        return ResponseEntity.ok(ApiResponse.success(
                ApplicationResponse.from(saved),
                "Application submitted: "
                        + saved.getApplicationNumber()));
    }

    /** Get application by ID. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApplicationResponse>>
            getApplication(@PathVariable Long id) {
        LoanApplication app =
                appService.getApplication(id);
        return ResponseEntity.ok(ApiResponse.success(
                ApplicationResponse.from(app)));
    }

    /** Verify application. CHECKER/ADMIN. */
    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApplicationResponse>>
            verify(@PathVariable Long id,
                    @Valid @RequestBody RemarksRequest req) {
        LoanApplication app =
                appService.verifyApplication(
                        id, req.remarks());
        return ResponseEntity.ok(ApiResponse.success(
                ApplicationResponse.from(app),
                "Application verified"));
    }

    /** Approve application. CHECKER/ADMIN. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApplicationResponse>>
            approve(@PathVariable Long id,
                    @Valid @RequestBody RemarksRequest req) {
        LoanApplication app =
                appService.approveApplication(
                        id, req.remarks());
        return ResponseEntity.ok(ApiResponse.success(
                ApplicationResponse.from(app),
                "Application approved"));
    }

    /** Reject application with mandatory reason. CHECKER/ADMIN. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApplicationResponse>>
            reject(@PathVariable Long id,
                    @Valid @RequestBody RejectRequest req) {
        LoanApplication app =
                appService.rejectApplication(
                        id, req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                ApplicationResponse.from(app),
                "Application rejected"));
    }

    /** Applications by customer CIF. */
    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ApplicationResponse>>>
            getByCustomer(
                    @PathVariable Long customerId) {
        var apps = appService
                .getApplicationsByCustomer(customerId);
        var items = apps.stream()
                .map(ApplicationResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    /** Applications by status (pipeline view). */
    @GetMapping("/status/{status}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ApplicationResponse>>>
            getByStatus(@PathVariable String status) {
        ApplicationStatus st =
                ApplicationStatus.valueOf(status);
        var apps = appService
                .getApplicationsByStatus(st);
        var items = apps.stream()
                .map(ApplicationResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Request DTOs ===

    public record SubmitApplicationRequest(
            @NotNull Long customerId,
            @NotNull Long branchId,
            @NotBlank String productType,
            @NotNull @Positive BigDecimal requestedAmount,
            @NotNull @Positive BigDecimal interestRate,
            @NotNull @Positive Integer tenureMonths,
            String purpose,
            String collateralReference,
            String disbursementAccountNumber,
            BigDecimal penalRate) {}

    public record RemarksRequest(
            @NotBlank String remarks) {}

    public record RejectRequest(
            @NotBlank String reason) {}

    // === Response DTOs ===

    public record ApplicationResponse(
            Long id,
            String applicationNumber,
            String status,
            String productType,
            BigDecimal requestedAmount,
            BigDecimal approvedAmount,
            BigDecimal interestRate,
            int tenureMonths,
            String purpose,
            String customerNumber,
            String branchCode,
            String applicationDate,
            String verifiedBy,
            String approvedBy,
            String rejectionReason) {
        static ApplicationResponse from(
                LoanApplication a) {
            return new ApplicationResponse(
                    a.getId(),
                    a.getApplicationNumber(),
                    a.getStatus() != null
                            ? a.getStatus().name() : null,
                    a.getProductType(),
                    a.getRequestedAmount(),
                    a.getApprovedAmount(),
                    a.getInterestRate(),
                    a.getTenureMonths(),
                    a.getPurpose(),
                    a.getCustomer() != null
                            ? a.getCustomer()
                            .getCustomerNumber()
                            : null,
                    a.getBranch() != null
                            ? a.getBranch()
                            .getBranchCode() : null,
                    a.getApplicationDate() != null
                            ? a.getApplicationDate()
                            .toString() : null,
                    a.getVerifiedBy(),
                    a.getApprovedBy(),
                    a.getRejectionReason());
        }
    }
}
