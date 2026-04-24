package com.finvanta.api;

import com.finvanta.domain.entity.FixedDeposit;
import com.finvanta.domain.enums.FdStatus;
import com.finvanta.repository.FixedDepositRepository;
import com.finvanta.service.FixedDepositService;
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
 * All financial operations delegate to FixedDepositService which handles
 * GL posting, CASA debit/credit, and audit trail via TransactionEngine.
 */
@RestController
@RequestMapping("/api/v1/fixed-deposits")
public class FixedDepositController {

    private final FixedDepositRepository fdRepo;
    private final FixedDepositService fdService;
    private final BranchAccessValidator branchValidator;

    public FixedDepositController(
            FixedDepositRepository fdRepo,
            FixedDepositService fdService,
            BranchAccessValidator branchValidator) {
        this.fdRepo = fdRepo;
        this.fdService = fdService;
        this.branchValidator = branchValidator;
    }

    // === Financial Operations ===

    /**
     * Book a new FD. GL: DR CASA / CR FD Deposits. MAKER/ADMIN.
     *
     * <p>CBS IDEMPOTENCY: Clients MAY supply an {@code Idempotency-Key} header
     * (RFC draft-ietf-httpapi-idempotency-key-header). When present, the service
     * layer deduplicates retries so a network retry of the same booking does not
     * create two FDs / two CASA debits. Absent header → legacy non-idempotent path.
     */
    @PostMapping("/book")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FdResponse>>
            bookFd(@Valid @RequestBody BookFdRequest req,
                    @RequestHeader(value = "Idempotency-Key", required = false)
                            String idempotencyKey) {
        FixedDeposit fd = fdService.bookFd(
                req.customerId(), req.branchId(),
                req.linkedAccountNumber(),
                req.principalAmount(), req.interestRate(),
                req.tenureDays(),
                req.interestPayoutMode(),
                req.autoRenewalMode(),
                req.nomineeName(),
                req.nomineeRelationship(),
                idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success(
                FdResponse.from(fd),
                "FD booked: " + fd.getFdAccountNumber()));
    }

    /** Premature closure with penalty. CHECKER/ADMIN. */
    @PostMapping("/{fdNumber}/premature-close")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FdResponse>>
            prematureClose(@PathVariable String fdNumber,
                    @RequestBody CloseRequest req) {
        FixedDeposit fd = fdService.prematureClose(
                fdNumber, req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                FdResponse.from(fd), "FD premature closed"));
    }

    /** Maturity closure. CHECKER/ADMIN. */
    @PostMapping("/{fdNumber}/maturity-close")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FdResponse>>
            maturityClose(@PathVariable String fdNumber) {
        FixedDeposit fd = fdService.maturityClose(fdNumber);
        return ResponseEntity.ok(ApiResponse.success(
                FdResponse.from(fd), "FD maturity closed"));
    }

    /** Mark lien for loan collateral. CHECKER/ADMIN. */
    @PostMapping("/{fdNumber}/lien/mark")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FdResponse>>
            markLien(@PathVariable String fdNumber,
                    @Valid @RequestBody LienRequest req) {
        FixedDeposit fd = fdService.markLien(
                fdNumber, req.lienAmount(),
                req.loanAccountNumber());
        return ResponseEntity.ok(ApiResponse.success(
                FdResponse.from(fd), "Lien marked"));
    }

    /** Release lien. CHECKER/ADMIN. */
    @PostMapping("/{fdNumber}/lien/release")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FdResponse>>
            releaseLien(@PathVariable String fdNumber) {
        FixedDeposit fd = fdService.releaseLien(fdNumber);
        return ResponseEntity.ok(ApiResponse.success(
                FdResponse.from(fd), "Lien released"));
    }

    // === Inquiry ===

    /** Get FD by account number. Branch access enforced. */
    @GetMapping("/{fdNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
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
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
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

    /**
     * CBS FD Response per Finacle TD_MASTER / Temenos IRIS Fixed Deposit.
     *
     * <p>Per RBI: nominee details, TDS, penalty rate, and customer CIF
     * are mandatory display fields on FD certificate and closure receipt.
     */
    public record FdResponse(
            Long id, String fdAccountNumber, String status,
            String currencyCode, String branchCode,
            // --- Customer (CIF linkage) ---
            Long customerId, String customerNumber, String customerName,
            // --- Principal ---
            BigDecimal principalAmount, BigDecimal currentPrincipal,
            BigDecimal maturityAmount,
            // --- Interest ---
            BigDecimal interestRate, BigDecimal effectiveRate,
            BigDecimal prematurePenaltyRate,
            String interestPayoutMode,
            BigDecimal accruedInterest, BigDecimal totalInterestPaid,
            BigDecimal ytdInterestPaid, BigDecimal ytdTdsDeducted,
            // --- Tenure / Dates ---
            int tenureDays,
            String bookingDate, String maturityDate, String closureDate,
            // --- Linked Account ---
            String linkedAccountNumber,
            // --- Renewal ---
            String autoRenewalMode, int renewalCount,
            // --- Lien ---
            boolean lienMarked, BigDecimal lienAmount,
            String lienLoanAccount,
            // --- Nomination ---
            String nomineeName, String nomineeRelationship) {
        static FdResponse from(FixedDeposit fd) {
            return new FdResponse(
                    fd.getId(), fd.getFdAccountNumber(),
                    fd.getStatus() != null
                            ? fd.getStatus().name() : null,
                    fd.getCurrencyCode(), fd.getBranchCode(),
                    fd.getCustomer() != null ? fd.getCustomer().getId() : null,
                    fd.getCustomer() != null
                            ? fd.getCustomer().getCustomerNumber() : null,
                    fd.getCustomer() != null
                            ? fd.getCustomer().getFullName() : null,
                    fd.getPrincipalAmount(), fd.getCurrentPrincipal(),
                    fd.getMaturityAmount(),
                    fd.getInterestRate(), fd.getEffectiveRate(),
                    fd.getPrematurePenaltyRate(),
                    fd.getInterestPayoutMode(),
                    fd.getAccruedInterest(), fd.getTotalInterestPaid(),
                    fd.getYtdInterestPaid(), fd.getYtdTdsDeducted(),
                    fd.getTenureDays(),
                    fd.getBookingDate() != null
                            ? fd.getBookingDate().toString() : null,
                    fd.getMaturityDate() != null
                            ? fd.getMaturityDate().toString() : null,
                    fd.getClosureDate() != null
                            ? fd.getClosureDate().toString() : null,
                    fd.getLinkedAccountNumber(),
                    fd.getAutoRenewalMode(), fd.getRenewalCount(),
                    fd.isLienMarked(), fd.getLienAmount(),
                    fd.getLienLoanAccount(),
                    fd.getNomineeName(), fd.getNomineeRelationship());
        }
    }
}
