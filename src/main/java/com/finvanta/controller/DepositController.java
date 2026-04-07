package com.finvanta.controller;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    public DepositController(DepositAccountService depositService,
                              BusinessDateService businessDateService,
                              CustomerRepository customerRepository,
                              BranchRepository branchRepository,
                              DepositAccountRepository depositAccountRepository) {
        this.depositService = depositService;
        this.businessDateService = businessDateService;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.depositAccountRepository = depositAccountRepository;
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
            mav.addObject("accounts", depositService.getActiveAccounts());
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                mav.addObject("accounts",
                    depositAccountRepository.findActiveByBranch(tenantId, branchId));
            } else {
                // CBS: No branch assigned — show empty list per fail-safe principle.
                // Per RBI Operational Risk: no-branch users must not see all data.
                mav.addObject("accounts", java.util.Collections.emptyList());
            }
        }
        mav.addObject("pageTitle", "CASA Accounts");
        return mav;
    }

    @GetMapping("/open")
    public ModelAndView openAccountForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("deposit/open");
        mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("pageTitle", "Open CASA Account");
        return mav;
    }

    @PostMapping("/open")
    public String openAccount(@RequestParam Long customerId,
                               @RequestParam Long branchId,
                               @RequestParam String accountType,
                               @RequestParam(required = false) String productCode,
                               @RequestParam(required = false) BigDecimal initialDeposit,
                               @RequestParam(required = false) String nomineeName,
                               @RequestParam(required = false) String nomineeRelationship,
                               RedirectAttributes ra) {
        try {
            DepositAccount account = depositService.openAccount(customerId, branchId,
                accountType, productCode != null ? productCode : accountType,
                initialDeposit, nomineeName, nomineeRelationship);
            ra.addFlashAttribute("success",
                "Account opened: " + account.getAccountNumber());
            return "redirect:/deposit/view/" + account.getAccountNumber();
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/deposit/open";
        }
    }

    @GetMapping("/view/{accountNumber}")
    public ModelAndView viewAccount(@PathVariable String accountNumber) {
        ModelAndView mav = new ModelAndView("deposit/view");
        DepositAccount account = depositService.getAccount(accountNumber);
        List<DepositTransaction> transactions = depositService.getMiniStatement(accountNumber, 20);
        mav.addObject("account", account);
        mav.addObject("transactions", transactions);
        mav.addObject("pageTitle", "Account: " + accountNumber);
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
    public String processDeposit(@PathVariable String accountNumber,
                                  @RequestParam BigDecimal amount,
                                  @RequestParam(required = false) String narration,
                                  RedirectAttributes ra) {
        try {
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new com.finvanta.util.BusinessException("INVALID_AMOUNT",
                    "Deposit amount must be positive");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            DepositTransaction txn = depositService.deposit(accountNumber, amount,
                businessDate, narration, null, "BRANCH");
            ra.addFlashAttribute("success",
                "Deposit of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
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
    public String processWithdrawal(@PathVariable String accountNumber,
                                     @RequestParam BigDecimal amount,
                                     @RequestParam(required = false) String narration,
                                     RedirectAttributes ra) {
        try {
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new com.finvanta.util.BusinessException("INVALID_AMOUNT",
                    "Withdrawal amount must be positive");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            DepositTransaction txn = depositService.withdraw(accountNumber, amount,
                businessDate, narration, null, "BRANCH");
            ra.addFlashAttribute("success",
                "Withdrawal of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
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
    public String processTransfer(@RequestParam String fromAccount,
                                   @RequestParam String toAccount,
                                   @RequestParam BigDecimal amount,
                                   @RequestParam(required = false) String narration,
                                   RedirectAttributes ra) {
        try {
            // CBS: Server-side validation — defense-in-depth per OWASP / RBI IT Governance
            if (amount == null || amount.signum() <= 0) {
                throw new com.finvanta.util.BusinessException("INVALID_AMOUNT",
                    "Transfer amount must be positive");
            }
            if (fromAccount == null || fromAccount.isBlank() || toAccount == null || toAccount.isBlank()) {
                throw new com.finvanta.util.BusinessException("MISSING_ACCOUNT",
                    "Both source and target accounts are required");
            }
            LocalDate businessDate = businessDateService.getCurrentBusinessDate();
            DepositTransaction txn = depositService.transfer(fromAccount, toAccount,
                amount, businessDate, narration, null);
            ra.addFlashAttribute("success",
                "Transfer of INR " + amount + " posted. Voucher: " + txn.getVoucherNumber());
            return "redirect:/deposit/view/" + fromAccount;
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/deposit/transfer";
        }
    }

    @PostMapping("/freeze/{accountNumber}")
    public String freezeAccount(@PathVariable String accountNumber,
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
    public String unfreezeAccount(@PathVariable String accountNumber,
                                   RedirectAttributes ra) {
        try {
            depositService.unfreezeAccount(accountNumber);
            ra.addFlashAttribute("success", "Account unfrozen: " + accountNumber);
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deposit/view/" + accountNumber;
    }

    @PostMapping("/close/{accountNumber}")
    public String closeAccount(@PathVariable String accountNumber,
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
}
