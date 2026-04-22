package com.finvanta.controller;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.StandingInstructionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionPreview;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS CASA (Current Account / Savings Account) Controller.
 *
 * Endpoints:
 *   GET  /deposit/accounts          - List all active CASA accounts
 *   GET  /deposit/open              - Open account form
 *   POST /deposit/open              - Submit account opening
 *   GET  /deposit/view/{accNo}      - Account details + mini statement
 *   GET  /deposit/deposit/{accNo}   - Deposit form
 *   POST /deposit/deposit/{accNo}   - Submit deposit
 *   GET  /deposit/withdraw/{accNo}  - Withdrawal form
 *   POST /deposit/withdraw/{accNo}  - Submit withdrawal
 *   GET  /deposit/transfer          - Transfer form
 *   POST /deposit/transfer          - Submit transfer
 *   POST /deposit/freeze/{accNo}    - Freeze account (ADMIN only)
 *   POST /deposit/unfreeze/{accNo}  - Unfreeze account (ADMIN only)
 *   POST /deposit/close/{accNo}     - Close account (CHECKER/ADMIN)
 *   POST /deposit/activate/{accNo}  - Activate pending account (CHECKER/ADMIN)
 *   POST /deposit/reversal/{txnRef} - Reverse transaction (CHECKER/ADMIN)
 *   GET  /deposit/statement/{accNo} - Account statement (date range)
 */
@Controller
@RequestMapping("/deposit")
public class DepositController {

    private static final Logger log = LoggerFactory.getLogger(DepositController.class);

    /** CBS: Allowed transaction types for the preview endpoint — whitelist per OWASP. */
    private static final Set<String> PREVIEW_TXN_TYPES = Set.of(
            "CASH_DEPOSIT", "CASH_WITHDRAWAL");

    private final DepositAccountService depositService;
    private final BusinessDateService businessDateService;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final StandingInstructionRepository siRepository;
    private final ProductMasterRepository productMasterRepository;
    private final BranchAccessValidator branchAccessValidator;
    private final TransactionEngine transactionEngine;
    private final ProductGLResolver glResolver;

    public DepositController(
            DepositAccountService depositService,
            BusinessDateService businessDateService,
            CustomerRepository customerRepository,
            BranchRepository branchRepository,
            DepositAccountRepository depositAccountRepository,
            StandingInstructionRepository siRepository,
            ProductMasterRepository productMasterRepository,
            BranchAccessValidator branchAccessValidator,
            TransactionEngine transactionEngine,
            ProductGLResolver glResolver) {
        this.depositService = depositService;
        this.businessDateService = businessDateService;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.siRepository = siRepository;
        this.productMasterRepository = productMasterRepository;
        this.branchAccessValidator = branchAccessValidator;
        this.transactionEngine = transactionEngine;
        this.glResolver = glResolver;
    }

    /**
     * CBS CASA Account List with branch isolation per Finacle BRANCH_CONTEXT / SOL.
     * MAKER/CHECKER: see only accounts at their home branch.
     * ADMIN/AUDITOR: sees all accounts across all branches (read-only for AUDITOR).
     * Per SecurityUtil.isAuditorRole(): AUDITOR is read-only, sees all branches.
     */
    @GetMapping("/accounts")
    public ModelAndView listAccounts() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/accounts");
        if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
            mav.addObject("accounts", depositService.getAllAccounts());
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                mav.addObject("accounts", depositAccountRepository.findActiveByBranch(tenantId, branchId));
            } else {
                // CBS: No branch assigned — show empty list per fail-safe principle.
                // Per RBI Operational Risk: no-branch users must not see all data.
                mav.addObject("accounts", java.util.Collections.emptyList());
            }
        }
        mav.addObject("pageTitle", "CASA Accounts");
        return mav;
    }

    /**
     * CBS CASA Account Search per Finacle ACCTINQ / Temenos ACCOUNT.ENQUIRY.
     * Searches by account number, customer CIF, or customer name.
     * Branch-scoped for MAKER/CHECKER per Finacle BRANCH_CONTEXT.
     * Per RBI Customer Protection Framework 2024: instant account lookup for complaints.
     */
    @GetMapping("/search")
    public ModelAndView searchAccounts(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/accounts");
        if (q != null && !q.isBlank() && q.trim().length() >= 2) {
            String trimmed = q.trim();
            if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
                mav.addObject("accounts", depositAccountRepository.searchAccounts(tenantId, trimmed));
            } else {
                Long branchId = SecurityUtil.getCurrentUserBranchId();
                if (branchId != null) {
                    mav.addObject("accounts",
                            depositAccountRepository.searchAccountsByBranch(tenantId, branchId, trimmed));
                } else {
                    mav.addObject("accounts", java.util.Collections.emptyList());
                }
            }
            mav.addObject("searchQuery", q);
        } else {
            // No query or too short — show default list
            return listAccounts();
        }
        mav.addObject("pageTitle", "CASA Accounts");
        return mav;
    }

    /**
     * CBS CASA Pipeline View per Finacle ACCTOPN workflow stages.
     * Shows accounts grouped by lifecycle stage — same pattern as /loan/applications.
     * Stage 1: Pending Activation (MAKER submitted → CHECKER to activate)
     * Stage 2: Active Accounts (operational)
     * Stage 3: Attention Required (DORMANT / FROZEN / INOPERATIVE)
     *
     * CBS Tier-1: Pipeline is branch-scoped for CHECKER (sees only their branch).
     * ADMIN sees all branches (for HO consolidated view).
     */
    @GetMapping("/pipeline")
    public ModelAndView pipeline() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/pipeline");
        if (SecurityUtil.isAdminRole()) {
            mav.addObject("pendingAccounts", depositAccountRepository.findPendingActivation(tenantId));
            mav.addObject("activeAccounts", depositAccountRepository.findActiveAccounts(tenantId));
            mav.addObject("attentionAccounts", depositAccountRepository.findAttentionRequired(tenantId));
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                // Filter pipeline by branch — only show accounts at user's branch
                mav.addObject("pendingAccounts", depositAccountRepository.findPendingActivation(tenantId).stream()
                        .filter(a -> a.getBranch() != null && branchId.equals(a.getBranch().getId()))
                        .toList());
                mav.addObject("activeAccounts", depositAccountRepository.findActiveByBranch(tenantId, branchId));
                mav.addObject("attentionAccounts", depositAccountRepository.findAttentionRequired(tenantId).stream()
                        .filter(a -> a.getBranch() != null && branchId.equals(a.getBranch().getId()))
                        .toList());
            } else {
                mav.addObject("pendingAccounts", java.util.Collections.emptyList());
                mav.addObject("activeAccounts", java.util.Collections.emptyList());
                mav.addObject("attentionAccounts", java.util.Collections.emptyList());
            }
        }
        mav.addObject("pageTitle", "CASA Account Pipeline");
        return mav;
    }

    @GetMapping("/open")
    public ModelAndView openAccountForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/open");
        // CBS Tier-1: Branch-scoped dropdowns per Finacle BRANCH_CONTEXT.
        // MAKER/CHECKER see only their branch's customers and their home branch.
        // ADMIN sees all customers and branches.
        if (SecurityUtil.isAdminRole()) {
            mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
            mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                mav.addObject("customers", customerRepository.findByTenantIdAndBranchIdAndActiveTrue(tenantId, branchId));
                branchRepository.findById(branchId)
                        .filter(b -> b.getTenantId().equals(tenantId))
                        .ifPresent(b -> mav.addObject("branches", java.util.List.of(b)));
            } else {
                mav.addObject("customers", java.util.Collections.emptyList());
                mav.addObject("branches", java.util.Collections.emptyList());
            }
        }
        mav.addObject("products", productMasterRepository.findActiveProducts(tenantId));
        mav.addObject("pageTitle", "Open CASA Account");
        return mav;
    }

    @PostMapping("/open")
    public String openAccount(
            @RequestParam Long customerId,
            @RequestParam Long branchId,
            @RequestParam String accountType,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String nomineeName,
            @RequestParam(required = false) String nomineeRelationship,
            RedirectAttributes ra) {
        try {
            // CBS: Construct OpenAccountRequest DTO from JSP form params.
            // JSP form only captures the original 7 fields — new fields are null.
            // Per @JsonIgnoreProperties(ignoreUnknown = true): null fields are safe.
            var req = new com.finvanta.api.DepositAccountController.OpenAccountRequest(
                    customerId, branchId, accountType,
                    productCode != null ? productCode : accountType,
                    null, // currencyCode — default INR
                    null, // initialDeposit — not used in maker-checker flow
                    null, null, null, null, // KYC fields
                    null, // fullName — will be null from JSP (backward compat)
                    null, null, null, null, // personal details
                    null, null, // contact
                    null, null, null, null, null, // address
                    null, null, null, // occupation
                    nomineeName, nomineeRelationship,
                    null, // usTaxResident
                    null, null, null); // config flags
            DepositAccount account = depositService.openAccount(req);
            ra.addFlashAttribute("success", "Account opened: " + account.getAccountNumber());
            return "redirect:/deposit/view/" + account.getAccountNumber();
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/deposit/open";
        }
    }

    @GetMapping("/view/{accountNumber}")
    public ModelAndView viewAccount(@PathVariable String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/view");
        DepositAccount account = depositService.getAccount(accountNumber);
        List<DepositTransaction> transactions = depositService.getMiniStatement(accountNumber, 20);
        mav.addObject("account", account);
        mav.addObject("transactions", transactions);
        mav.addObject("pageTitle", "Account: " + accountNumber);

        // CBS: Standing Instructions linked to this CASA account (for SI list + registration form)
        mav.addObject(
                "standingInstructions",
                siRepository.findByTenantIdAndSourceAccountNumberOrderByPriorityAsc(tenantId, accountNumber));
        // Active CASA accounts for SI target selection (internal transfer destination — all branches)
        mav.addObject("activeAccounts", depositService.getActiveAccounts());
        // CBS Tier-1: Pass branch context for display
        mav.addObject("accountBranchCode", account.getBranch() != null ? account.getBranch().getBranchCode() : "--");

        return mav;
    }

    /**
     * CBS Tier-1: Transaction Preview (Dry-Run Validation) per Finacle TRAN_PREVIEW.
     *
     * <p>AJAX endpoint called from deposit/withdraw/transfer forms when the operator
     * enters an amount. Returns a JSON TransactionPreview with all validation check
     * results, maker-checker status, and GL journal line preview — WITHOUT committing
     * any GL posting.
     *
     * <p>Per RBI Operational Risk Guidelines: operators must verify transaction details
     * and see any blockers BEFORE committing an irreversible posting.
     *
     * @param accountNumber Account to validate against
     * @param amount        Transaction amount
     * @param txnType       Transaction type (CASH_DEPOSIT, CASH_WITHDRAWAL)
     * @param narration     Optional narration
     * @return JSON TransactionPreview
     */
    @GetMapping("/preview/{accountNumber}")
    @ResponseBody
    public TransactionPreview previewTransaction(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam String txnType,
            @RequestParam(required = false) String narration) {
        try {
            // CBS: Whitelist txnType to prevent arbitrary strings flowing into
            // TransactionRequest, audit trail narrations, and log files.
            if (!PREVIEW_TXN_TYPES.contains(txnType)) {
                throw new BusinessException("INVALID_TXN_TYPE",
                        "Unsupported transaction type for preview: " + txnType);
            }
            DepositAccount account = depositService.getAccount(accountNumber);
            // CBS Tier-1: Branch access validation — preview exposes account balance,
            // customer name, and branch details. Without this check, a MAKER at Branch A
            // could view account details at Branch B via the preview AJAX endpoint.
            // The actual deposit/withdraw service methods validate branch access, but
            // the preview would leak data before the operator even clicks Post.
            branchAccessValidator.validateAccess(account.getBranch());
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();

            // Resolve GL codes based on transaction type
            String depositGl = resolveDepositGl(account);
            List<JournalLineRequest> lines;
            if ("CASH_DEPOSIT".equals(txnType)) {
                lines = List.of(
                        new JournalLineRequest(
                                GLConstants.BANK_OPERATIONS,
                                DebitCredit.DEBIT, amount, "Cash deposit"),
                        new JournalLineRequest(
                                depositGl, DebitCredit.CREDIT, amount,
                                "Credit " + accountNumber));
            } else {
                // CASH_WITHDRAWAL
                lines = List.of(
                        new JournalLineRequest(
                                depositGl, DebitCredit.DEBIT, amount,
                                "Debit " + accountNumber),
                        new JournalLineRequest(
                                GLConstants.BANK_OPERATIONS,
                                DebitCredit.CREDIT, amount, "Cash withdrawal"));
            }

            TransactionRequest request = TransactionRequest.builder()
                    .sourceModule("DEPOSIT")
                    .transactionType(txnType)
                    .accountReference(accountNumber)
                    .amount(amount)
                    .valueDate(businessDate)
                    .branchCode(account.getBranch().getBranchCode())
                    .narration(narration != null ? narration : (
                            "CASH_DEPOSIT".equals(txnType) ? "Cash deposit" : "Cash withdrawal"))
                    .journalLines(lines)
                    .build();

            TransactionPreview preview = transactionEngine.validate(request);

            // CBS: Enrich the engine preview with account-specific info.
            // Re-build with ALL engine checks copied, plus account context.
            TransactionPreview.Builder enriched = TransactionPreview.builder()
                    .accountNumber(accountNumber)
                    .accountHolder(account.getCustomer().getFirstName() + " " + account.getCustomer().getLastName())
                    .branchCode(account.getBranch().getBranchCode())
                    .currentBalance(account.getLedgerBalance())
                    .projectedBalance("CASH_DEPOSIT".equals(txnType)
                            ? account.getLedgerBalance().add(amount)
                            : account.getLedgerBalance().subtract(amount))
                    .amount(amount)
                    .transactionType(txnType)
                    .valueDate(businessDate)
                    .narration(request.getNarration())
                    .requiresApproval(preview.isRequiresApproval())
                    .journalLines(preview.getJournalLines());

            // Copy ALL engine validation checks into the enriched preview
            for (TransactionPreview.CheckResult check : preview.getChecks()) {
                enriched.addCheck(check.step(), check.category(), check.description(), check.passed(), check.detail());
            }

            // Add account-specific checks not covered by the engine
            boolean accountActive = account.isActive();
            enriched.addCheck("ACCOUNT_STATUS", "Account",
                    "Account " + accountNumber + " is " + account.getAccountStatus(),
                    accountActive,
                    accountActive ? "ACTIVE — transactions allowed" : "Status " + account.getAccountStatus() + " — transactions blocked");

            if ("CASH_WITHDRAWAL".equals(txnType)) {
                boolean hasFunds = account.hasSufficientFunds(amount);
                enriched.addCheck("SUFFICIENT_FUNDS", "Account",
                        "Sufficient funds for withdrawal of INR " + amount,
                        hasFunds,
                        hasFunds ? "Available: INR " + account.getEffectiveAvailable()
                                : "Insufficient: available INR " + account.getEffectiveAvailable() + " < requested INR " + amount);
                if (account.getMinimumBalance() != null && account.getMinimumBalance().signum() > 0) {
                    BigDecimal postBalance = account.getLedgerBalance().subtract(amount);
                    boolean minBalOk = postBalance.compareTo(account.getMinimumBalance()) >= 0;
                    enriched.addCheck("MINIMUM_BALANCE", "Account",
                            "Post-withdrawal balance INR " + postBalance + " ≥ minimum INR " + account.getMinimumBalance(),
                            minBalOk,
                            minBalOk ? "OK" : "BREACH: post-balance INR " + postBalance + " < minimum INR " + account.getMinimumBalance());
                }
            }

            return enriched.build();
        } catch (Exception e) {
            log.warn("Transaction preview failed: account={}, type={}, error={}", accountNumber, txnType, e.getMessage());
            // Return a preview with a single FAILED check so the UI shows the error
            // instead of silently hiding the panel (empty checks = hidden).
            return TransactionPreview.builder()
                    .amount(amount)
                    .transactionType(txnType)
                    .accountNumber(accountNumber)
                    .addCheck("PREVIEW_ERROR", "System",
                            "Transaction preview could not be completed",
                            false,
                            e.getMessage() != null ? e.getMessage() : "Unexpected error — contact support")
                    .build();
        }
    }

    /**
     * CBS: Sanitize a string for CSV output per OWASP CSV Injection guidelines.
     * If the value starts with =, +, -, @, \t, or \r, prefix with a single quote
     * to prevent Excel/LibreOffice from interpreting it as a formula.
     * Also escapes embedded double-quotes per RFC 4180.
     * Per RBI Inspection Manual: inspectors open CSV exports in Excel — formula
     * injection in narration fields could execute arbitrary commands on their machines.
     */
    private static String csvSafe(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (!escaped.isEmpty()) {
            char first = escaped.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                escaped = "'" + escaped;
            }
        }
        return escaped;
    }

    /**
     * Resolve deposit GL code for preview — mirrors DepositAccountServiceImpl.glForAccount().
     * CBS CRITICAL: Must use ProductGLResolver (same as service layer) to show correct GL
     * codes in the preview. Hardcoded fallback would show wrong GL for product-configured
     * accounts, undermining the preview's purpose of operator verification before posting.
     */
    private String resolveDepositGl(DepositAccount account) {
        // CBS: Type-safe product existence check via ProductGLResolver (same logic as service layer)
        var product = glResolver.getProduct(account.getProductCode());
        if (product != null && product.getGlLoanAsset() != null) {
            return product.getGlLoanAsset();
        }
        // Product not configured — fall back to type-based GL
        return account.isSavings()
                ? GLConstants.SB_DEPOSITS
                : GLConstants.CA_DEPOSITS;
    }

    @GetMapping("/deposit/{accountNumber}")
    public ModelAndView depositForm(@PathVariable String accountNumber) {
        ModelAndView mav = new ModelAndView("deposit/deposit");
        mav.addObject("account", depositService.getAccount(accountNumber));
        mav.addObject("pageTitle", "Deposit");
        return mav;
    }

    @PostMapping("/deposit/{accountNumber}")
    public String processDeposit(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String narration,
            RedirectAttributes ra) {
        try {
            log.debug("Deposit request received: account={}, amount={}, narration={}", accountNumber, amount, narration);
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException("INVALID_AMOUNT", "Deposit amount must be positive");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            log.debug("Deposit: calling service. account={}, amount={}, businessDate={}", accountNumber, amount, businessDate);
            DepositTransaction txn =
                    depositService.deposit(accountNumber, amount, businessDate, narration, null, "BRANCH");
            log.debug("Deposit completed: account={}, voucher={}, txnRef={}", accountNumber, txn.getVoucherNumber(), txn.getTransactionRef());
            ra.addFlashAttribute("success", "Deposit of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
        } catch (Exception e) {
            log.error("Deposit failed: account={}, amount={}, error={}", accountNumber, amount, e.getMessage(), e);
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    @GetMapping("/withdraw/{accountNumber}")
    public ModelAndView withdrawForm(@PathVariable String accountNumber) {
        ModelAndView mav = new ModelAndView("deposit/withdraw");
        mav.addObject("account", depositService.getAccount(accountNumber));
        mav.addObject("pageTitle", "Withdraw");
        return mav;
    }

    @PostMapping("/withdraw/{accountNumber}")
    public String processWithdrawal(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String narration,
            RedirectAttributes ra) {
        try {
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException("INVALID_AMOUNT", "Withdrawal amount must be positive");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            DepositTransaction txn =
                    depositService.withdraw(accountNumber, amount, businessDate, narration, null, "BRANCH");
            ra.addFlashAttribute(
                    "success", "Withdrawal of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    @GetMapping("/transfer")
    public ModelAndView transferForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/transfer");
        // CBS Tier-1: Transfer source accounts restricted to user's branch.
        // Target accounts show all active accounts (inter-branch transfer is allowed
        // but the source must be at the user's branch per Finacle BRANCH_CONTEXT).
        if (SecurityUtil.isAdminRole()) {
            mav.addObject("accounts", depositService.getActiveAccounts());
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                mav.addObject("accounts", depositAccountRepository.findActiveByBranch(tenantId, branchId));
            } else {
                mav.addObject("accounts", java.util.Collections.emptyList());
            }
        }
        // Target accounts: show all active (inter-branch transfer target can be any branch)
        mav.addObject("allAccounts", depositService.getActiveAccounts());
        mav.addObject("pageTitle", "Fund Transfer");
        return mav;
    }

    @PostMapping("/transfer")
    public String processTransfer(
            @RequestParam String fromAccount,
            @RequestParam String toAccount,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String narration,
            RedirectAttributes ra) {
        try {
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException("INVALID_AMOUNT", "Transfer amount must be positive");
            }
            if (fromAccount == null || fromAccount.isBlank() || toAccount == null || toAccount.isBlank()) {
                throw new BusinessException("MISSING_ACCOUNT", "Both source and target accounts are required");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            DepositTransaction txn =
                    depositService.transfer(fromAccount, toAccount, amount, businessDate, narration, null);
            ra.addFlashAttribute(
                    "success", "Transfer of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
            return "redirect:/deposit/view/" + fromAccount;
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/deposit/transfer";
        }
    }

    @PostMapping("/freeze/{accountNumber}")
    public String freezeAccount(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "TOTAL_FREEZE") String freezeType,
            @RequestParam String reason,
            RedirectAttributes ra) {
        try {
            depositService.freezeAccount(accountNumber, freezeType, reason);
            ra.addFlashAttribute("success", "Account frozen: " + accountNumber);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    @PostMapping("/unfreeze/{accountNumber}")
    public String unfreezeAccount(@PathVariable String accountNumber, RedirectAttributes ra) {
        try {
            depositService.unfreezeAccount(accountNumber);
            ra.addFlashAttribute("success", "Account unfrozen: " + accountNumber);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    @PostMapping("/close/{accountNumber}")
    public String closeAccount(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "Customer request") String reason,
            RedirectAttributes ra) {
        try {
            depositService.closeAccount(accountNumber, reason);
            ra.addFlashAttribute("success", "Account closed: " + accountNumber);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    /**
     * CBS Account Maintenance per Finacle ACCTMOD / Temenos ACCOUNT.MODIFY.
     * Modifies operational parameters on an existing ACTIVE account.
     * CHECKER/ADMIN only. All changes audited with before/after state.
     */
    @PostMapping("/maintain/{accountNumber}")
    public String maintainAccount(
            @PathVariable String accountNumber,
            @RequestParam(required = false) String nomineeName,
            @RequestParam(required = false) String nomineeRelationship,
            @RequestParam(required = false) String jointHolderMode,
            @RequestParam(required = false) Boolean chequeBookEnabled,
            @RequestParam(required = false) Boolean debitCardEnabled,
            @RequestParam(required = false) BigDecimal dailyWithdrawalLimit,
            @RequestParam(required = false) BigDecimal dailyTransferLimit,
            @RequestParam(required = false) BigDecimal odLimit,
            @RequestParam(required = false) BigDecimal interestRate,
            @RequestParam(required = false) BigDecimal minimumBalance,
            RedirectAttributes ra) {
        try {
            depositService.maintainAccount(
                    accountNumber, nomineeName, nomineeRelationship, jointHolderMode,
                    chequeBookEnabled, debitCardEnabled,
                    dailyWithdrawalLimit, dailyTransferLimit, odLimit,
                    interestRate, minimumBalance);
            ra.addFlashAttribute("success", "Account maintained: " + accountNumber);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    /**
     * CBS Account Activation — CHECKER/ADMIN only.
     * Per Finacle ACCTOPN: activates a PENDING_ACTIVATION account after maker-checker approval.
     * Transitions the account from PENDING_ACTIVATION → ACTIVE.
     */
    @PostMapping("/activate/{accountNumber}")
    public String activateAccount(@PathVariable String accountNumber, RedirectAttributes ra) {
        try {
            depositService.activateAccount(accountNumber);
            ra.addFlashAttribute("success", "Account activated: " + accountNumber);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    /**
     * CBS Transaction Reversal — CHECKER/ADMIN only (enforced via SecurityConfig).
     * Per Finacle TRAN_REVERSAL: creates contra GL entries and restores account balance.
     * Original transaction is marked reversed (never deleted per CBS audit rules).
     */
    @PostMapping("/reversal/{transactionRef}")
    public String reverseTransaction(
            @PathVariable String transactionRef,
            @RequestParam String reason,
            @RequestParam String accountNumber,
            RedirectAttributes ra) {
        try {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException("REASON_REQUIRED", "Reversal reason is mandatory");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            depositService.reverseTransaction(transactionRef, reason, businessDate);
            ra.addFlashAttribute("success", "Transaction reversed: " + transactionRef);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    /**
     * CBS Account Statement CSV Export per Finacle STMT_DETAIL / RBI Passbook Norms.
     * Per RBI: customer must be able to download statement in machine-readable format.
     * Per RBI Inspection Manual: inspectors request statements in CSV for analysis.
     * CSV chosen over PDF: no external dependency, universally parseable, Excel-compatible.
     */
    @GetMapping("/statement/{accountNumber}/export")
    public ResponseEntity<byte[]> exportStatement(
            @PathVariable String accountNumber,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        DepositAccount account = depositService.getAccount(accountNumber);
        LocalDate to;
        LocalDate from;
        try {
            to = (toDate != null && !toDate.isBlank()) ? LocalDate.parse(toDate)
                    : businessDateService.getCurrentBusinessDate();
            from = (fromDate != null && !fromDate.isBlank()) ? LocalDate.parse(fromDate)
                    : to.minusDays(30);
        } catch (Exception e) {
            to = businessDateService.getCurrentBusinessDate();
            from = to.minusDays(30);
        }
        List<DepositTransaction> txns = depositService.getStatement(accountNumber, from, to);

        StringBuilder csv = new StringBuilder();
        // CBS: Header row per RBI statement format
        csv.append("Date,Transaction Ref,Type,Channel,Narration,Debit,Credit,Balance,Voucher\n");
        for (DepositTransaction t : txns) {
            csv.append(t.getValueDate()).append(',');
            csv.append('"').append(csvSafe(t.getTransactionRef())).append("\",");
            csv.append(t.getTransactionType()).append(',');
            csv.append(csvSafe(t.getChannel())).append(',');
            csv.append('"').append(csvSafe(t.getNarration())).append("\",");
            // CBS: Debit/Credit split per RBI passbook format
            if ("DEBIT".equals(t.getDebitCredit())) {
                csv.append(t.getAmount()).append(",,");
            } else {
                csv.append(',').append(t.getAmount()).append(',');
            }
            csv.append(t.getBalanceAfter()).append(',');
            csv.append(csvSafe(t.getVoucherNumber()));
            csv.append('\n');
        }

        String filename = "Statement_" + accountNumber + "_" + from + "_to_" + to + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * CBS Account Statement — date-range filtered transaction history.
     * Per RBI passbook/statement requirements and Finacle STMT_DETAIL.
     */
    @GetMapping("/statement/{accountNumber}")
    public ModelAndView accountStatement(
            @PathVariable String accountNumber,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        ModelAndView mav = new ModelAndView("deposit/statement");
        DepositAccount account = depositService.getAccount(accountNumber);
        mav.addObject("account", account);
        mav.addObject("pageTitle", "Statement: " + accountNumber);

        if (fromDate != null && toDate != null && !fromDate.isBlank() && !toDate.isBlank()) {
            LocalDate from;
            LocalDate to;
            try {
                from = LocalDate.parse(fromDate);
                to = LocalDate.parse(toDate);
            } catch (Exception e) {
                mav.addObject("error", "Invalid date format. Use YYYY-MM-DD.");
                LocalDate defaultTo = businessDateService.getCurrentBusinessDate();
                LocalDate defaultFrom = defaultTo.minusDays(30);
                mav.addObject("transactions", depositService.getStatement(accountNumber, defaultFrom, defaultTo));
                mav.addObject("fromDate", defaultFrom.toString());
                mav.addObject("toDate", defaultTo.toString());
                return mav;
            }
            mav.addObject("transactions", depositService.getStatement(accountNumber, from, to));
            mav.addObject("fromDate", fromDate);
            mav.addObject("toDate", toDate);
        } else {
            // Default: last 30 days
            LocalDate to = businessDateService.getCurrentBusinessDate();
            LocalDate from = to.minusDays(30);
            mav.addObject("transactions", depositService.getStatement(accountNumber, from, to));
            mav.addObject("fromDate", from.toString());
            mav.addObject("toDate", to.toString());
        }
        return mav;
    }
}
