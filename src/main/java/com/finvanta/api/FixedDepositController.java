package com.finvanta.api;

import com.finvanta.domain.entity.FixedDeposit;
import com.finvanta.domain.enums.FdStatus;
import com.finvanta.repository.FixedDepositRepository;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

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
 * CBS Fixed Deposit REST API per Finacle TD_API / Temenos IRIS Fixed Deposit.
 *
 * Thin orchestration layer. All GL posting and business logic will be
 * delegated to FixedDepositService (to be implemented).
 *
 * Per RBI Banking Regulation Act / Finacle TD_MASTER:
 * - Book FD from linked CASA (atomic debit CASA + credit FD GL)
 * - Premature closure with penalty rate reduction
 * - Maturity closure with full interest credit
 * - Lien mark/release for FD-backed loans
 *
 * CBS Role Matrix:
 *   MAKER   → book FD
 *   CHECKER → close FD, lien operations
 *   ADMIN   → all operations
 *
 * NOTE: bookFd, prematureClose, maturityClose, markLien, releaseLien
 * will delegate to FixedDepositService once implemented. For now,
 * inquiry endpoints are fully functional.
 */
@RestController
@RequestMapping("/api/v1/fixed-deposits")
public class FixedDepositController {

    private final FixedDepositRepository fdRepo;
    private final BranchAccessValidator branchValidator;

    public FixedDepositController(
            FixedDepositRepository fdRepo,
            BranchAccessValidator branchValidator) {
        this.fdRepo = fdRepo;
        this.branchValidator = branchValidator;
    }

    // === Inquiry ===

    /** Get FD by account number. Branch access enforced. */
    @GetMapping("/{fdNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FdResponse>>
            getFd(@PathVariable String fdNumber) {
        String tid = TenantContext.getCurrentTenant();
        FixedDeposit fd = fdRepo
                .findByTenantIdAndFdAccountNumber(
                        tid, fdNumber)
                .orElseThrow(() -> new BusinessException(
                        "FD_NOT_FOUND", fdNumber));
        branchValidator.validateAccess(fd.getBranch());
        return ResponseEntity.ok(ApiResponse.success(
                FdResponse.from(fd)));
    }

    /** FDs by customer CIF. */
    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<FdResponse>>>
            getByCustomer(
                    @PathVariable Long customerId) {
        String tid = TenantContext.getCurrentTenant();
        var fds = fdRepo.findByTenantIdAndCustomerId(
                tid, customerId);
        var items = fds.stream()
                .map(FdResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    /** Active FDs for tenant (dashboard). */
    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<FdResponse>>>
            getActiveFds() {
        String tid = TenantContext.getCurrentTenant();
        var fds = fdRepo.findByTenantIdAndStatus(
                tid, FdStatus.ACTIVE);
        var items = fds.stream()
                .map(FdResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Request DTOs ===

    public record BookFdRequest(
            @NotNull Long customerId,
            @NotNull Long branchId,
            @NotBlank String linkedAccountNumber,
            @NotNull @Positive BigDecimal principalAmount,
            @NotNull @Positive BigDecimal interestRate,
            @NotNull @Positive Integer tenureDays,
            String interestPayoutMode,
            String autoRenewalMode,
            String nomineeName,
            String nomineeRelationship) {}

    public record CloseRequest(String reason) {}

    public record LienRequest(
            @NotNull @Positive BigDecimal lienAmount,
            @NotBlank String loanAccountNumber) {}

    // === Response DTOs ===

    public record FdResponse(
            Long id,
            String fdAccountNumber,
            String status,
            BigDecimal principalAmount,
            BigDecimal currentPrincipal,
            BigDecimal interestRate,
            BigDecimal effectiveRate,
            String interestPayoutMode,
            BigDecimal accruedInterest,
            BigDecimal totalInterestPaid,
            BigDecimal maturityAmount,
            int tenureDays,
            String bookingDate,
            String maturityDate,
            String closureDate,
            String linkedAccountNumber,
            String autoRenewalMode,
            int renewalCount,
            boolean lienMarked,
            BigDecimal lienAmount,
            String customerNumber,
            String branchCode) {
        static FdResponse from(FixedDeposit fd) {
            return new FdResponse(
                    fd.getId(),
                    fd.getFdAccountNumber(),
                    fd.getStatus() != null
                            ? fd.getStatus().name() : null,
                    fd.getPrincipalAmount(),
                    fd.getCurrentPrincipal(),
                    fd.getInterestRate(),
                    fd.getEffectiveRate(),
                    fd.getInterestPayoutMode(),
                    fd.getAccruedInterest(),
                    fd.getTotalInterestPaid(),
                    fd.getMaturityAmount(),
                    fd.getTenureDays(),
                    fd.getBookingDate() != null
                            ? fd.getBookingDate().toString()
                            : null,
                    fd.getMaturityDate() != null
                            ? fd.getMaturityDate().toString()
                            : null,
                    fd.getClosureDate() != null
                            ? fd.getClosureDate().toString()
                            : null,
                    fd.getLinkedAccountNumber(),
                    fd.getAutoRenewalMode(),
                    fd.getRenewalCount(),
                    fd.isLienMarked(),
                    fd.getLienAmount(),
                    fd.getCustomer() != null
                            ? fd.getCustomer()
                            .getCustomerNumber() : null,
                    fd.getBranchCode());
        }
    }
}
