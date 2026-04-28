package com.finvanta.cbs.modules.teller.controller;

import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;
import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.CashWithdrawalRequest;
import com.finvanta.cbs.modules.teller.dto.request.DenominationEntry;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.dto.response.CashWithdrawalResponse;
import com.finvanta.cbs.modules.teller.exception.FicnDetectedException;
import com.finvanta.cbs.modules.teller.service.TellerService;
import com.finvanta.util.BusinessException;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Teller MVC (JSP) Controller per CBS TELLER_UI standard.
 *
 * <p>Glue layer between the JSP teller UI and {@link TellerService}. The
 * controller:
 * <ul>
 *   <li>Renders the open-till and cash-deposit screens with the current till
 *       state prefilled.</li>
 *   <li>Reassembles the denomination grid from the flat
 *       {@code denom_NOTE_500=12} form fields into a
 *       {@code List<DenominationEntry>}. This is the only place the flat-form
 *       encoding exists -- the service layer works with the typed list.</li>
 *   <li>Catches {@link BusinessException} and routes the BFF-style
 *       {@code errorCode} + message back through flash attributes so the JSP
 *       can render the same remediation text the REST BFF would show (codes
 *       are wired in {@link com.finvanta.cbs.common.exception.CbsApiExceptionHandler}).</li>
 * </ul>
 *
 * <p>Per Tier-1 CBS standards this controller carries NO business logic and
 * NO {@code @Transactional}. The transaction boundary is inside
 * {@link TellerService#cashDeposit}.
 */
@Controller
@RequestMapping("/teller")
public class TellerWebController {

    private static final Logger log = LoggerFactory.getLogger(TellerWebController.class);

    /**
     * Canonical denomination order for the JSP grid. Matches the
     * {@link IndianCurrencyDenomination} enum declaration (largest note first,
     * coins last). The grid template iterates this list so the same
     * ordering is used on the deposit form and on the printed receipt.
     */
    private static final List<IndianCurrencyDenomination> GRID_ORDER =
            Arrays.asList(IndianCurrencyDenomination.values());

    /**
     * CTR / PMLA threshold (INR) that triggers the PAN / Form 60 panel in
     * the JSP. Kept as a controller constant (not a Spring property) so the
     * UI-side threshold moves in lockstep with the service-side threshold
     * in {@code TellerServiceImpl.CTR_PAN_THRESHOLD}. Changes must be made
     * in both places, and the ArchUnit rule for hardcoded-number drift is
     * expected to catch any skew in a future commit.
     */
    private static final BigDecimal CTR_PAN_THRESHOLD = new BigDecimal("50000");

    private final TellerService tellerService;

    public TellerWebController(TellerService tellerService) {
        this.tellerService = tellerService;
    }

    // =====================================================================
    // Open Till
    // =====================================================================

    @GetMapping("/till/open")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public String showOpenTill(Model model) {
        // Pre-render attempt to surface the current till if one already
        // exists -- lets the JSP show a friendly "till already open" panel
        // instead of failing on POST.
        try {
            model.addAttribute("currentTill", tellerService.getMyCurrentTill());
        } catch (BusinessException ignored) {
            // No till today; the JSP shows the open form.
        }
        return "teller/open-till";
    }

    @PostMapping("/till/open")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public String submitOpenTill(
            @RequestParam BigDecimal openingBalance,
            @RequestParam(required = false) BigDecimal tillCashLimit,
            @RequestParam(required = false) String remarks,
            RedirectAttributes redirect) {
        try {
            TellerTill till = tellerService.openTill(
                    new OpenTillRequest(openingBalance, tillCashLimit, remarks));
            redirect.addFlashAttribute("success",
                    "Till opened (status: " + till.getStatus() + ")");
            return "redirect:/teller/cash-deposit";
        } catch (BusinessException ex) {
            log.warn("Till open failed: {} -- {}", ex.getErrorCode(), ex.getMessage());
            redirect.addFlashAttribute("errorCode", ex.getErrorCode());
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/teller/till/open";
        }
    }

    // =====================================================================
    // Cash Deposit
    // =====================================================================

    @GetMapping("/cash-deposit")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public String showCashDeposit(Model model) {
        // Surface the current till so the JSP can render the teller's
        // cash-in-hand badge and block the form when no till is open.
        try {
            model.addAttribute("currentTill", tellerService.getMyCurrentTill());
        } catch (BusinessException ex) {
            model.addAttribute("errorCode", ex.getErrorCode());
            model.addAttribute("error", ex.getMessage());
        }
        model.addAttribute("denominationOrder", GRID_ORDER);
        model.addAttribute("ctrPanThreshold", CTR_PAN_THRESHOLD);
        return "teller/cash-deposit";
    }

    @PostMapping("/cash-deposit")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public String submitCashDeposit(
            @RequestParam String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String narration,
            @RequestParam String idempotencyKey,
            @RequestParam String depositorName,
            @RequestParam(required = false) String depositorMobile,
            @RequestParam(required = false) String panNumber,
            @RequestParam(required = false) String form60Reference,
            HttpServletRequest httpRequest,
            RedirectAttributes redirect) {
        try {
            List<DenominationEntry> denoms = parseDenominationsFromForm(httpRequest);
            CashDepositRequest req = new CashDepositRequest(
                    accountNumber, amount, denoms, idempotencyKey,
                    depositorName, depositorMobile, panNumber, narration, form60Reference);
            CashDepositResponse receipt = tellerService.cashDeposit(req);

            if (receipt.pendingApproval()) {
                redirect.addFlashAttribute("success",
                        "Cash deposit submitted for checker approval. Ref: "
                                + receipt.transactionRef());
            } else {
                redirect.addFlashAttribute("success",
                        "Cash deposit POSTED. Ref: " + receipt.transactionRef()
                                + " | Voucher: " + receipt.voucherNumber());
            }
            // Include the receipt so the JSP can show the printable slip.
            redirect.addFlashAttribute("lastReceipt", receipt);
            return "redirect:/teller/cash-deposit";
        } catch (FicnDetectedException fe) {
            // CBS FICN per RBI Master Direction on Counterfeit Notes:
            // the deposit was rejected and counterfeit notes were impounded.
            // The register row + audit log are already committed (REQUIRES_NEW
            // sub-tx in FicnRegisterService.recordDetection); we only need to
            // surface the printable acknowledgement slip to the JSP via flash
            // attributes. The slip MUST appear separately from the generic
            // error banner because the customer leaves the counter holding a
            // copy of it -- this is regulatory evidence, not a UI nicety.
            //
            // The catch block is intentionally placed BEFORE the generic
            // BusinessException catch -- Java try/catch ordering matters here
            // (unlike @ExceptionHandler ordering) because FicnDetectedException
            // extends BusinessException; placing this second would render it
            // unreachable.
            log.warn("Teller cash deposit FICN-rejected: ref={} fir-required={}",
                    fe.getAcknowledgement().registerRef(),
                    fe.getAcknowledgement().firRequired());
            redirect.addFlashAttribute("errorCode", fe.getErrorCode());
            redirect.addFlashAttribute("error", fe.getMessage());
            // The JSP renders ficnAcknowledgement separately from the
            // standard `lastReceipt` -- the two are mutually exclusive on
            // any given POST so there is no naming collision.
            redirect.addFlashAttribute("ficnAcknowledgement", fe.getAcknowledgement());
            return "redirect:/teller/cash-deposit";
        } catch (BusinessException ex) {
            log.warn("Teller cash deposit failed: {} -- {}", ex.getErrorCode(), ex.getMessage());
            redirect.addFlashAttribute("errorCode", ex.getErrorCode());
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/teller/cash-deposit";
        }
    }

    // =====================================================================
    // Cash Withdrawal
    // =====================================================================

    @GetMapping("/cash-withdrawal")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public String showCashWithdrawal(Model model) {
        // Same pre-render contract as cash-deposit: surface the current till
        // so the JSP can render the cash-in-hand badge and disable the form
        // when no till is open. Reuses the same model attribute names so
        // the layout/header chrome stays identical across both screens.
        try {
            model.addAttribute("currentTill", tellerService.getMyCurrentTill());
        } catch (BusinessException ex) {
            model.addAttribute("errorCode", ex.getErrorCode());
            model.addAttribute("error", ex.getMessage());
        }
        model.addAttribute("denominationOrder", GRID_ORDER);
        model.addAttribute("ctrPanThreshold", CTR_PAN_THRESHOLD);
        return "teller/cash-withdrawal";
    }

    @PostMapping("/cash-withdrawal")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public String submitCashWithdrawal(
            @RequestParam String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String narration,
            @RequestParam String idempotencyKey,
            @RequestParam String beneficiaryName,
            @RequestParam(required = false) String beneficiaryMobile,
            @RequestParam(required = false) String chequeNumber,
            HttpServletRequest httpRequest,
            RedirectAttributes redirect) {
        try {
            List<DenominationEntry> denoms = parseDenominationsFromForm(httpRequest);
            CashWithdrawalRequest req = new CashWithdrawalRequest(
                    accountNumber, amount, denoms, idempotencyKey,
                    beneficiaryName, beneficiaryMobile, chequeNumber, narration);
            CashWithdrawalResponse receipt = tellerService.cashWithdrawal(req);

            if (receipt.pendingApproval()) {
                redirect.addFlashAttribute("success",
                        "Cash withdrawal submitted for checker approval. Ref: "
                                + receipt.transactionRef());
            } else {
                redirect.addFlashAttribute("success",
                        "Cash withdrawal POSTED. Ref: " + receipt.transactionRef()
                                + " | Voucher: " + receipt.voucherNumber());
            }
            redirect.addFlashAttribute("lastReceipt", receipt);
            return "redirect:/teller/cash-withdrawal";
        } catch (BusinessException ex) {
            log.warn("Teller cash withdrawal failed: {} -- {}", ex.getErrorCode(), ex.getMessage());
            redirect.addFlashAttribute("errorCode", ex.getErrorCode());
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/teller/cash-withdrawal";
        }
    }

    /**
     * Reassembles the flat {@code denom_NOTE_500=12} parameters submitted by
     * the JSP grid into typed {@link DenominationEntry} rows. Also reads the
     * per-denomination {@code counterfeit_NOTE_500=2} field. Zero rows are
     * skipped client-side but also filtered here as a defensive measure.
     *
     * <p>Structured as a static-ish helper so the parsing is the ONLY place
     * the flat-form encoding exists -- the service layer receives the typed
     * list and never touches raw request parameters.
     */
    private List<DenominationEntry> parseDenominationsFromForm(HttpServletRequest request) {
        List<DenominationEntry> rows = new ArrayList<>();
        for (IndianCurrencyDenomination denom : GRID_ORDER) {
            String unitParam = request.getParameter("denom_" + denom.name());
            String counterfeitParam = request.getParameter("counterfeit_" + denom.name());
            long unitCount = parseLongOrZero(unitParam);
            long counterfeitCount = parseLongOrZero(counterfeitParam);
            if (unitCount == 0 && counterfeitCount == 0) {
                // Skip empty rows so the service-layer coalesce() doesn't
                // produce a flood of zero-valued rows. The validator also
                // filters zeros, so this is cosmetic but keeps DB inserts lean.
                continue;
            }
            rows.add(new DenominationEntry(denom, unitCount, counterfeitCount));
        }
        return rows;
    }

    private long parseLongOrZero(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            long v = Long.parseLong(s.trim());
            return v < 0 ? 0L : v;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
