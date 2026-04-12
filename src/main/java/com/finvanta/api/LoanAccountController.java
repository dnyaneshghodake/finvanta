package com.finvanta.api;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanApplicationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Loan Account REST API per Finacle LOAN_API / Temenos IRIS Lending.
 *
 * Thin orchestration layer over LoanAccountService — no business logic here.
 * All GL posting, NPA classification, IRAC compliance, and maker-checker
 * enforcement reside in LoanAccountServiceImpl and TransactionEngine.
 *
 * CBS Role Matrix for Loans:
 *   MAKER   → apply, repayment, prepayment, fee charge
 *   CHECKER → verify, approve, reject, disburse, create account, reverse
 *   ADMIN   → all + write-off, restructure, rate reset
 *
 * Per RBI IRAC Norms / Fair Lending Code 2023:
 * - NPA classification at 90+ DPD (Days Past Due)
 * - Penal interest on overdue principal only (not on interest)
 * - No prepayment penalty on floating rate loans
 * - Provisioning: Standard 0.4%, Sub-standard 15%, Doubtful 25-100%, Loss 100%
 */
@RestController
@RequestMapping("/api/v1/loans")
public class LoanAccountController {

    private final LoanAccountService loanService;
    private final LoanApplicationService loanAppService;
    private final BusinessDateService businessDateService;

    public LoanAccountController(
            LoanAccountService loanService,
            LoanApplicationService loanAppService,
            BusinessDateService businessDateService) {
        this.loanService = loanService;
        this.loanAppService = loanAppService;
        this.businessDateService = businessDateService;
    }

    // === Loan Lifecycle ===

    /** Create loan account from approved application. CHECKER/ADMIN. */
    @PostMapping("/create-account/{applicationId}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanResponse>>
            createAccount(@PathVariable Long applicationId) {
        LoanAccount account = loanService.createLoanAccount(applicationId);
        return ResponseEntity.ok(ApiResponse.success(
                LoanResponse.from(account), "Loan account created"));
    }

    /** Disburse full loan amount. CHECKER/ADMIN. */
    @PostMapping("/{accountNumber}/disburse")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanResponse>>
            disburse(@PathVariable String accountNumber) {
        LoanAccount account = loanService.disburseLoan(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(
                LoanResponse.from(account), "Loan disbursed"));
    }

    /** Disburse a tranche for multi-disbursement products. CHECKER/ADMIN. */
    @PostMapping("/{accountNumber}/disburse-tranche")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanResponse>>
            disburseTranche(@PathVariable String accountNumber,
                    @RequestBody TrancheRequest req) {
        LoanAccount account = loanService.disburseTranche(
                accountNumber, req.amount(), req.narration());
        return ResponseEntity.ok(ApiResponse.success(
                LoanResponse.from(account), "Tranche disbursed"));
    }

    // === Financial Operations ===

    /** Process loan repayment (EMI or ad-hoc). MAKER/ADMIN. */
    @PostMapping("/{accountNumber}/repayment")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanTxnResponse>>
            processRepayment(@PathVariable String accountNumber,
                    @RequestBody RepaymentRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        LoanTransaction txn = loanService.processRepayment(
                accountNumber, req.amount(), bd, req.idempotencyKey());
        return ResponseEntity.ok(ApiResponse.success(LoanTxnResponse.from(txn)));
    }

    /**
     * Prepayment/foreclosure per RBI Fair Lending Code 2023.
     * No penalty on floating rate loans. MAKER/ADMIN.
     */
    @PostMapping("/{accountNumber}/prepayment")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanTxnResponse>>
            processPrepayment(@PathVariable String accountNumber,
                    @RequestBody RepaymentRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        LoanTransaction txn = loanService.processPrepayment(
                accountNumber, req.amount(), bd);
        return ResponseEntity.ok(ApiResponse.success(
                LoanTxnResponse.from(txn), "Prepayment processed"));
    }

    /** Charge fee (processing, documentation, etc.). MAKER/ADMIN. */
    @PostMapping("/{accountNumber}/fee")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanTxnResponse>>
            chargeFee(@PathVariable String accountNumber,
                    @RequestBody FeeRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        LoanTransaction txn = loanService.chargeFee(
                accountNumber, req.amount(), req.feeType(), bd);
        return ResponseEntity.ok(ApiResponse.success(LoanTxnResponse.from(txn)));
    }

    /** Reverse a loan transaction. CHECKER/ADMIN. */
    @PostMapping("/reversal/{transactionRef}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanTxnResponse>>
            reverseTransaction(@PathVariable String transactionRef,
                    @RequestBody ReversalRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        LoanTransaction txn = loanService.reverseTransaction(
                transactionRef, req.reason(), bd);
        return ResponseEntity.ok(ApiResponse.success(
                LoanTxnResponse.from(txn), "Transaction reversed"));
    }

    // === Rate Management ===

    /** Floating rate reset per RBI EBLR/MCLR Framework. ADMIN only. */
    @PostMapping("/{accountNumber}/rate-reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LoanResponse>>
            resetFloatingRate(@PathVariable String accountNumber,
                    @RequestBody RateResetRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        LoanAccount account = loanService.resetFloatingRate(
                accountNumber, req.newBenchmarkRate(), bd);
        return ResponseEntity.ok(ApiResponse.success(
                LoanResponse.from(account), "Rate reset applied"));
    }

    // === NPA / Write-Off ===

    /** Write off NPA Loss account. ADMIN only per RBI IRAC. */
    @PostMapping("/{accountNumber}/write-off")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LoanResponse>>
            writeOff(@PathVariable String accountNumber) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        LoanAccount account = loanService.writeOffAccount(accountNumber, bd);
        return ResponseEntity.ok(ApiResponse.success(
                LoanResponse.from(account), "Account written off"));
    }

    // === Inquiry ===

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoanResponse>>
            getAccount(@PathVariable String accountNumber) {
        LoanAccount account = loanService.getAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(LoanResponse.from(account)));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<LoanResponse>>>
            getActiveAccounts() {
        var accounts = loanService.getActiveAccounts();
        var items = accounts.stream().map(LoanResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Request DTOs ===

    public record TrancheRequest(BigDecimal amount, String narration) {}

    public record RepaymentRequest(
            BigDecimal amount, String idempotencyKey) {}

    public record FeeRequest(BigDecimal amount, String feeType) {}

    public record ReversalRequest(String reason) {}

    public record RateResetRequest(BigDecimal newBenchmarkRate) {}

    // === Response DTOs ===

    public record LoanResponse(
            Long id, String accountNumber, String accountStatus,
            String productCode, String loanType,
            BigDecimal sanctionedAmount, BigDecimal disbursedAmount,
            BigDecimal principalOutstanding, BigDecimal interestOutstanding,
            BigDecimal totalOutstanding, BigDecimal interestRate,
            String npaClassification, String branchCode,
            String disbursementDate, String maturityDate) {
        static LoanResponse from(LoanAccount a) {
            return new LoanResponse(
                    a.getId(), a.getAccountNumber(),
                    a.getAccountStatus() != null ? a.getAccountStatus().name() : null,
                    a.getProductCode(),
                    a.getLoanType() != null ? a.getLoanType().name() : null,
                    a.getSanctionedAmount(), a.getDisbursedAmount(),
                    a.getPrincipalOutstanding(), a.getInterestOutstanding(),
                    a.getTotalOutstanding(), a.getInterestRate(),
                    a.getNpaClassification() != null
                            ? a.getNpaClassification().name() : "STANDARD",
                    a.getBranch() != null ? a.getBranch().getBranchCode() : null,
                    a.getDisbursementDate() != null
                            ? a.getDisbursementDate().toString() : null,
                    a.getMaturityDate() != null
                            ? a.getMaturityDate().toString() : null);
        }
    }

    public record LoanTxnResponse(
            Long id, String transactionRef, String transactionType,
            BigDecimal amount, String valueDate,
            String narration, String voucherNumber) {
        static LoanTxnResponse from(LoanTransaction t) {
            return new LoanTxnResponse(
                    t.getId(), t.getTransactionRef(),
                    t.getTransactionType(),
                    t.getAmount(),
                    t.getValueDate() != null ? t.getValueDate().toString() : null,
                    t.getNarration(), t.getVoucherNumber());
        }
    }
}
