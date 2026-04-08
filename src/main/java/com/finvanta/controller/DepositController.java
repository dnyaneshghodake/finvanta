package com.finvanta.controller;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.StandingInstructionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final DepositAccountService depositService;
    private final BusinessDateService businessDateService;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final StandingInstructionRepository siRepository;
    private final ProductMasterRepository productMasterRepository;

    public DepositController(
            DepositAccountService depositService,
            BusinessDateService businessDateService,
            CustomerRepository customerRepository,
            BranchRepository branchRepository,
            DepositAccountRepository depositAccountRepository,
            StandingInstructionRepository siRepository,
            ProductMasterRepository productMasterRepository) {
        this.depositService = depositService;
        this.businessDateService = businessDateService;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.siRepository = siRepository;
        this.productMasterRepository = productMasterRepository;
    }

    /**
     * CBS CASA Account List with branch isolation per Finacle BRANCH_CONTEXT / SOL.
     * MAKER/CHECKER: see only accounts at their home branch.
     * ADMIN: sees all accounts across all branches.
     */
    @GetMapping("/accounts")
    public ModelAndView listAccounts() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/accounts");
        if (SecurityUtil.isAdminRole()) {
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
     * CBS CASA Pipeline View per Finacle ACCTOPN workflow stages.
     * Shows accounts grouped by lifecycle stage — same pattern as /loan/applications.
     * Stage 1: Pending Activation (MAKER submitted → CHECKER to activate)
     * Stage 2: Active Accounts (operational)
     * Stage 3: Attention Required (DORMANT / FROZEN / INOPERATIVE)
     */
    @GetMapping("/pipeline")
    public ModelAndView pipeline() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/pipeline");
        mav.addObject("pendingAccounts", depositAccountRepository.findPendingActivation(tenantId));
        mav.addObject("activeAccounts", depositAccountRepository.findActiveAccounts(tenantId));
        mav.addObject("attentionAccounts", depositAccountRepository.findAttentionRequired(tenantId));
        mav.addObject("pageTitle", "CASA Account Pipeline");
        return mav;
    }

    @GetMapping("/open")
    public ModelAndView openAccountForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/open");
        mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
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
            @RequestParam(required = false) BigDecimal initialDeposit,
            @RequestParam(required = false) String nomineeName,
            @RequestParam(required = false) String nomineeRelationship,
            RedirectAttributes ra) {
        try {
            DepositAccount account = depositService.openAccount(
                    customerId,
                    branchId,
                    accountType,
                    productCode != null ? productCode : accountType,
                    initialDeposit,
                    nomineeName,
                    nomineeRelationship);
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
        // Active CASA accounts for SI target selection (internal transfer destination)
        mav.addObject("activeAccounts", depositService.getActiveAccounts());

        return mav;
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
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException("INVALID_AMOUNT", "Deposit amount must be positive");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            DepositTransaction txn =
                    depositService.deposit(accountNumber, amount, businessDate, narration, null, "BRANCH");
            ra.addFlashAttribute("success", "Deposit of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
        } catch (Exception e) {
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
        ModelAndView mav = new ModelAndView("deposit/transfer");
        mav.addObject("accounts", depositService.getActiveAccounts());
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
