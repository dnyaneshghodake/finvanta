package com.finvanta.charge;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.charge.GstTaxResolver.GstSplit;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.ChargeDefinition;
import com.finvanta.domain.entity.ChargeTransaction;
import com.finvanta.domain.enums.ChargeEventType;
import com.finvanta.domain.enums.ChargeTransactionStatus;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.ChargeDefinitionRepository;
import com.finvanta.repository.ChargeTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Unified Charge Kernel per Finacle CHG_ENGINE / Temenos FT.COMMISSION / BNP PROCESSING.CHARGE.
 *
 * <p>Consolidated charge engine for ALL CBS modules (Clearing, CASA, Loan).
 * Replaces the former split between {@code com.finvanta.charge.ChargeEngine}
 * (cross-cutting) and {@code com.finvanta.batch.ChargeEngine} (loan-only) --
 * per Tier-1 CBS design, there must be exactly ONE enforcement point for fee
 * calculation and GL posting so that waivers and reversals can be guaranteed
 * symmetric.
 *
 * <p><b>Operations</b> (RBI Fair Practices Code 2023 §5 + IT Governance Direction
 * 2023 §8.2):
 * <ol>
 *   <li>{@link #levyCharge} -- resolve definition, compute fee, split GST per
 *       {@link GstTaxResolver} (CGST+SGST intra-state / IGST inter-state), post
 *       the multi-leg journal via {@code TransactionEngine}, persist an
 *       immutable {@code ChargeTransaction} with status {@code LEVIED}.</li>
 *   <li>{@link #waiveCharge} -- policy-driven income giveup. Posts a contra journal
 *       that credits the customer and reverses fee income (including GST legs).
 *       Status transitions {@code LEVIED -> WAIVED}.</li>
 *   <li>{@link #reverseCharge} -- operational rollback (source transaction reversed
 *       or incorrect levy detected). Posts a fully symmetric contra journal that
 *       mirrors the original legs one-for-one. Status transitions {@code LEVIED ->
 *       REVERSED}. Per RBI FPC 2023 §5.7: reversals MUST be the mirror image of
 *       the original posting so GL trial balance remains clean.</li>
 * </ol>
 *
 * <p><b>GL Flow for {@link #levyCharge}</b>:
 * <pre>
 *   Intra-state (branch state == customer state):
 *     DR Customer Account GL    totalDebit   (baseFee + CGST + SGST)
 *     CR Fee Income             baseFee
 *     CR CGST Payable (2200)    cgstAmount
 *     CR SGST Payable (2201)    sgstAmount
 *
 *   Inter-state (branch state != customer state):
 *     DR Customer Account GL    totalDebit   (baseFee + IGST)
 *     CR Fee Income             baseFee
 *     CR IGST Payable (2202)    igstAmount
 * </pre>
 *
 * <p>All terminal state transitions emit audit events and are idempotent --
 * double-waiving or double-reversing a charge throws {@code BusinessException}
 * with a stable error code.
 */
@Service
public class ChargeKernel {

    private static final Logger log =
            LoggerFactory.getLogger(ChargeKernel.class);

    private final ChargeDefinitionRepository defRepo;
    private final ChargeTransactionRepository txnRepo;
    private final BranchRepository branchRepo;
    private final TransactionEngine txnEngine;
    private final BusinessDateService bizDateSvc;
    private final AuditService auditSvc;
    private final GstTaxResolver gstResolver;

    public ChargeKernel(
            ChargeDefinitionRepository defRepo,
            ChargeTransactionRepository txnRepo,
            BranchRepository branchRepo,
            TransactionEngine txnEngine,
            BusinessDateService bizDateSvc,
            AuditService auditSvc,
            GstTaxResolver gstResolver) {
        this.defRepo = defRepo;
        this.txnRepo = txnRepo;
        this.branchRepo = branchRepo;
        this.txnEngine = txnEngine;
        this.bizDateSvc = bizDateSvc;
        this.auditSvc = auditSvc;
        this.gstResolver = gstResolver;
    }

    // ---------------------------------------------------------------------
    // LEVY
    // ---------------------------------------------------------------------

    /**
     * Backward-compatible levy without explicit customer state. Falls back to
     * intra-state (CGST + SGST) split. Prefer {@link #levyCharge(ChargeEventType,
     * String, String, BigDecimal, String, String, String, String, String)} to
     * enable correct IGST classification per GST Act 2017 §5.
     *
     * @return {@link ChargeResult} for the posted fee, or {@code null} if no
     *         applicable charge definition exists (free transaction).
     */
    @Transactional
    public ChargeResult levyCharge(
            ChargeEventType eventType,
            String accountNumber,
            String customerGlCode,
            BigDecimal txnAmount,
            String productCode,
            String sourceModule,
            String sourceRef,
            String branchCode) {
        return levyCharge(eventType, accountNumber, customerGlCode,
                txnAmount, productCode, sourceModule, sourceRef,
                branchCode, null);
    }

    /**
     * Levy a charge on a customer account with state-aware GST split.
     *
     * @param eventType         chargeable event (CASA, clearing, loan)
     * @param accountNumber     customer account to debit
     * @param customerGlCode    customer GL code (e.g. {@code SB_DEPOSITS})
     * @param txnAmount         base transaction amount for percentage charges
     * @param productCode       product code for definition resolution (null = global)
     * @param sourceModule      originating module (CLEARING / DEPOSIT / LOAN)
     * @param sourceRef         source transaction reference (UTR / txnRef)
     * @param branchCode        branch where the charge is levied
     * @param customerStateCode customer's state (ISO-like 2-letter code, e.g. {@code "MH"}).
     *                          Null/blank -> intra-state (CGST + SGST) per GST Act §12
     *                          conservative default (protects customer ITC).
     * @return {@link ChargeResult} or {@code null} if no charge applies
     */
    @Transactional
    public ChargeResult levyCharge(
            ChargeEventType eventType,
            String accountNumber,
            String customerGlCode,
            BigDecimal txnAmount,
            String productCode,
            String sourceModule,
            String sourceRef,
            String branchCode,
            String customerStateCode) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();

        ChargeDefinition def = resolveChargeDefinition(tid, eventType, productCode);
        if (def == null) {
            return null;
        }

        BigDecimal baseFee = computeBaseFee(def, txnAmount);
        if (baseFee.signum() <= 0) {
            return null;
        }

        Branch branch = branchRepo
                .findByTenantIdAndBranchCode(tid, branchCode)
                .orElse(null);
        // CBS: Use regionCode (2-letter ISO-like code, e.g. "MH", "KA", "DL") for
        // GST place-of-supply comparison, NOT state (full name, e.g. "Maharashtra").
        // GstTaxResolver.resolve() compares branchStateCode with customerStateCode via
        // equalsIgnoreCase(); using the full state name would always mismatch against
        // the 2-letter customerStateCode, causing every charge to be incorrectly
        // classified as inter-state (IGST) instead of intra-state (CGST+SGST) --
        // a GST Act 2017 compliance violation and ITC mismatch at the customer's end.
        String branchStateCode = branch != null ? branch.getRegionCode() : null;

        GstSplit gst = gstResolver.resolve(
                baseFee, def.isGstApplicable(), branchStateCode, customerStateCode);
        BigDecimal totalDebit = baseFee
                .add(gst.cgst())
                .add(gst.sgst())
                .add(gst.igst());

        // Build the multi-leg journal. The customer leg is always DR; fee income and
        // each GST head are CR. Per Finacle CHG_ENGINE: the customer leg debit must
        // equal the sum of all credits to the last paisa or the TransactionEngine
        // balance-validation will reject the posting.
        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(
                customerGlCode, DebitCredit.DEBIT, totalDebit,
                def.getChargeName() + " charge"));
        lines.add(new JournalLineRequest(
                def.getGlFeeIncome(), DebitCredit.CREDIT, baseFee,
                def.getChargeName()));
        // CBS: GST credit legs must be posted independently for CGST and SGST so
        // that a paisa-rounding asymmetry (e.g. baseFee INR 0.03 where totalGst
        // rounds up to 0.01 but cgst = 0.03 * 9% = 0.0027 rounds to 0.00) does
        // not silently drop the SGST credit leg. If the SGST leg were gated by
        // cgst > 0, the totalDebit (which includes gst.sgst()) would exceed the
        // sum of emitted credits and TransactionEngine's balance check would
        // reject the posting with ACCOUNTING_UNBALANCED. Per Finacle CHG_ENGINE
        // / Temenos FT.COMMISSION: every non-zero GST component must map to its
        // own CR line, irrespective of the sibling component's rounded value.
        if (gst.igst().signum() > 0) {
            lines.add(new JournalLineRequest(
                    GLConstants.IGST_PAYABLE, DebitCredit.CREDIT, gst.igst(),
                    "IGST on " + def.getChargeName()));
        } else {
            if (gst.cgst().signum() > 0) {
                lines.add(new JournalLineRequest(
                        GLConstants.CGST_PAYABLE, DebitCredit.CREDIT, gst.cgst(),
                        "CGST on " + def.getChargeName()));
            }
            if (gst.sgst().signum() > 0) {
                lines.add(new JournalLineRequest(
                        GLConstants.SGST_PAYABLE, DebitCredit.CREDIT, gst.sgst(),
                        "SGST on " + def.getChargeName()));
            }
        }

        TransactionResult result = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CHARGE")
                        .transactionType("CHG_" + eventType.name())
                        .accountReference(accountNumber)
                        .amount(totalDebit)
                        .valueDate(bd)
                        .branchCode(branchCode)
                        .narration(def.getChargeName()
                                + " | " + sourceModule + ":" + sourceRef)
                        .journalLines(lines)
                        .systemGenerated(true)
                        .initiatedBy("SYSTEM")
                        .build());

        ChargeTransaction chgTxn = new ChargeTransaction();
        chgTxn.setTenantId(tid);
        chgTxn.setEventType(eventType);
        chgTxn.setChargeDefinition(def);
        chgTxn.setAccountNumber(accountNumber);
        if (branch != null) {
            chgTxn.setBranch(branch);
            chgTxn.setBranchCode(branchCode);
        }
        chgTxn.setValueDate(bd);
        chgTxn.setSourceModule(sourceModule);
        chgTxn.setSourceRef(sourceRef);
        chgTxn.setTransactionAmount(txnAmount);
        chgTxn.setBaseFee(baseFee);
        chgTxn.setCgstAmount(gst.cgst());
        chgTxn.setSgstAmount(gst.sgst());
        chgTxn.setIgstAmount(gst.igst());
        chgTxn.setCustomerStateCode(customerStateCode);
        chgTxn.setCustomerGlCode(customerGlCode);
        chgTxn.setTotalDebit(totalDebit);
        chgTxn.setStatus(ChargeTransactionStatus.LEVIED);
        chgTxn.setJournalEntryId(result.getJournalEntryId());
        chgTxn.setVoucherNumber(result.getVoucherNumber());
        chgTxn.setPostedAt(LocalDateTime.now());
        chgTxn.setCreatedBy("SYSTEM");
        txnRepo.save(chgTxn);

        auditSvc.logEvent("ChargeTransaction",
                chgTxn.getId(), "CHARGE_LEVIED",
                null, sourceRef, "CHARGE",
                eventType.name() + " | INR " + baseFee
                        + " + GST " + gst.totalGst()
                        + (gst.isInterState() ? " (IGST)" : " (CGST+SGST)")
                        + " = " + totalDebit
                        + " | " + accountNumber);
        log.info("Charge levied: event={}, acct={}, fee={}, gst={} ({}), total={}",
                eventType, accountNumber, baseFee,
                gst.totalGst(), gst.isInterState() ? "IGST" : "CGST+SGST", totalDebit);

        return new ChargeResult(
                def.getId(),
                chgTxn.getId(),
                baseFee,
                gst.cgst(), gst.sgst(), gst.igst(),
                totalDebit,
                result.getJournalEntryId(),
                result.getVoucherNumber());
    }

    // ---------------------------------------------------------------------
    // WAIVE
    // ---------------------------------------------------------------------

    /**
     * Waive a previously levied charge (policy-driven income giveup).
     * Posts a contra journal that fully reverses the original legs and
     * transitions status {@code LEVIED -> WAIVED}.
     *
     * <p>Per RBI FPC 2023: only charges flagged {@code waivable} on the
     * definition can be waived -- regulatory charges (stamp duty, TDS) and
     * GST legs against non-waivable definitions are rejected.
     */
    @Transactional
    public ChargeTransaction waiveCharge(
            Long chargeTransactionId, String reason) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED",
                    "Waiver reason mandatory per RBI");
        }

        ChargeTransaction chgTxn = loadOwnedOrThrow(chargeTransactionId, tid);
        if (chgTxn.getStatus() != ChargeTransactionStatus.LEVIED) {
            throw new BusinessException("INVALID_STATE",
                    "Charge cannot be waived in status " + chgTxn.getStatus());
        }
        ChargeDefinition def = chgTxn.getChargeDefinition();
        if (!def.isWaivable()) {
            throw new BusinessException("NOT_WAIVABLE",
                    def.getChargeName() + " is not waivable per policy");
        }

        List<JournalLineRequest> contraLines = buildContraLines(
                chgTxn, def, "Waiver: ");
        // CBS: Credit the EXACT same customer GL that was debited at levy time.
        // Per RBI FPC 2023 §5.7 / Finacle CHG_MASTER: contra journals must be
        // the mirror image of the original posting. customerGlCode is persisted
        // at levy time; legacy records (created before this column existed) fall
        // back to SB_DEPOSITS as the conservative default.
        String customerGl = chgTxn.getCustomerGlCode() != null
                ? chgTxn.getCustomerGlCode() : GLConstants.SB_DEPOSITS;
        contraLines.add(new JournalLineRequest(
                customerGl,
                DebitCredit.CREDIT,
                chgTxn.getTotalDebit(),
                "Charge waiver credit"));

        txnEngine.execute(TransactionRequest.builder()
                .sourceModule("CHARGE")
                .transactionType("CHG_WAIVER")
                .accountReference(chgTxn.getAccountNumber())
                .amount(chgTxn.getTotalDebit())
                .valueDate(bd)
                .branchCode(chgTxn.getBranchCode())
                .narration("Charge waiver: " + def.getChargeName()
                        + " -- " + reason)
                .journalLines(contraLines)
                .systemGenerated(false)
                .initiatedBy(user)
                .build());

        chgTxn.setWaived(true);
        chgTxn.setWaiverReason(reason);
        chgTxn.setWaivedBy(user);
        chgTxn.setUpdatedBy(user);
        chgTxn.setStatus(ChargeTransactionStatus.WAIVED);
        txnRepo.save(chgTxn);

        auditSvc.logEvent("ChargeTransaction",
                chgTxn.getId(), "CHARGE_WAIVED",
                chgTxn.getEventType().name(), "WAIVED",
                "CHARGE",
                "Waived by " + user + ": " + reason
                        + " | INR " + chgTxn.getTotalDebit()
                        + " | " + chgTxn.getAccountNumber());
        log.info("Charge waived: id={}, reason={}, by={}",
                chargeTransactionId, reason, user);
        return chgTxn;
    }

    // ---------------------------------------------------------------------
    // REVERSE
    // ---------------------------------------------------------------------

    /**
     * Reverse a previously levied charge (operational rollback).
     *
     * <p>Distinct from {@link #waiveCharge}: a waiver is a <b>policy</b> decision
     * (the bank chooses to forgo income); a reversal is a <b>correction</b>
     * (the original levy was incorrect, e.g. the source transaction itself was
     * reversed, or a duplicate fee was posted). Per Finacle CHG_MASTER / Temenos
     * FT.CHARGE.REVERSE: reversals are the mirror image of the original journal
     * -- every DR becomes a CR and vice versa -- so the net GL impact is zero
     * and the trial balance remains clean.
     *
     * <p>Unlike waivers, reversals are allowed regardless of the definition's
     * {@code waivable} flag because operational rollbacks are mandatory per RBI
     * Fair Practices Code 2023 §5.7 whenever the underlying fee was wrongly
     * charged.
     *
     * @param chargeTransactionId persisted charge to reverse
     * @param reason              operational reason (e.g. "duplicate levy",
     *                            "source NEFT returned")
     * @return updated {@link ChargeTransaction} with status {@code REVERSED}
     */
    @Transactional
    public ChargeTransaction reverseCharge(
            Long chargeTransactionId, String reason) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED",
                    "Reversal reason mandatory per RBI FPC 2023");
        }

        ChargeTransaction chgTxn = loadOwnedOrThrow(chargeTransactionId, tid);
        if (chgTxn.getStatus() != ChargeTransactionStatus.LEVIED) {
            throw new BusinessException("INVALID_STATE",
                    "Charge cannot be reversed in status " + chgTxn.getStatus());
        }
        ChargeDefinition def = chgTxn.getChargeDefinition();

        // Symmetric mirror-image contra: every CR becomes a DR and the customer
        // leg becomes a CR. Per RBI FPC 2023 §5.7 this is the ONLY accepted
        // reversal pattern -- any drift from the original legs would leave fee
        // income or GST Payable non-zero on the trial balance.
        List<JournalLineRequest> contraLines = buildContraLines(
                chgTxn, def, "Reversal: ");
        // CBS: Credit the EXACT same customer GL that was debited at levy time.
        // Per RBI FPC 2023 §5.7: reversals must be the mirror image of the
        // original posting so the net GL impact is zero on every head.
        String customerGl = chgTxn.getCustomerGlCode() != null
                ? chgTxn.getCustomerGlCode() : GLConstants.SB_DEPOSITS;
        contraLines.add(new JournalLineRequest(
                customerGl,
                DebitCredit.CREDIT,
                chgTxn.getTotalDebit(),
                "Reversal credit: " + def.getChargeName()));

        TransactionResult reversalResult = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CHARGE")
                        .transactionType("CHG_REVERSAL")
                        .accountReference(chgTxn.getAccountNumber())
                        .amount(chgTxn.getTotalDebit())
                        .valueDate(bd)
                        .branchCode(chgTxn.getBranchCode())
                        .narration("Charge reversal: "
                                + def.getChargeName()
                                + " -- " + reason)
                        .journalLines(contraLines)
                        .systemGenerated(false)
                        .initiatedBy(user)
                        .build());

        chgTxn.setStatus(ChargeTransactionStatus.REVERSED);
        chgTxn.setReversalReason(reason);
        chgTxn.setReversedBy(user);
        chgTxn.setReversedAt(LocalDateTime.now());
        chgTxn.setReversalJournalEntryId(reversalResult.getJournalEntryId());
        chgTxn.setReversalVoucherNumber(reversalResult.getVoucherNumber());
        chgTxn.setUpdatedBy(user);
        txnRepo.save(chgTxn);

        auditSvc.logEvent("ChargeTransaction",
                chgTxn.getId(), "CHARGE_REVERSED",
                chgTxn.getEventType().name(), "REVERSED",
                "CHARGE",
                "Reversed by " + user + ": " + reason
                        + " | INR " + chgTxn.getTotalDebit()
                        + " | " + chgTxn.getAccountNumber()
                        + " | reversalJournal=" + reversalResult.getJournalRef());
        log.info("Charge reversed: id={}, reason={}, by={}, reversalJournal={}",
                chargeTransactionId, reason, user, reversalResult.getJournalRef());
        return chgTxn;
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    /** Resolve charge definition: product-specific first, global fallback. */
    ChargeDefinition resolveChargeDefinition(
            String tenantId, ChargeEventType eventType, String productCode) {
        List<ChargeDefinition> defs = defRepo.findApplicableCharges(
                tenantId, eventType, productCode);
        return defs.isEmpty() ? null : defs.get(0);
    }

    /** Compute base fee: FLAT returns amount, PERCENTAGE applies with min/max caps. */
    BigDecimal computeBaseFee(ChargeDefinition def, BigDecimal txnAmount) {
        if ("FLAT".equals(def.getChargeType())) {
            return def.getChargeAmount();
        }
        if ("PERCENTAGE".equals(def.getChargeType())) {
            BigDecimal fee = txnAmount
                    .multiply(def.getChargePercentage())
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (def.getMinCharge() != null
                    && fee.compareTo(def.getMinCharge()) < 0) {
                fee = def.getMinCharge();
            }
            if (def.getMaxCharge() != null
                    && fee.compareTo(def.getMaxCharge()) > 0) {
                fee = def.getMaxCharge();
            }
            return fee;
        }
        return BigDecimal.ZERO;
    }

    /**
     * Build the CR-reversal legs of a contra journal (fee income DR + GST legs DR).
     * The caller is responsible for appending the CR-customer leg.
     */
    private List<JournalLineRequest> buildContraLines(
            ChargeTransaction chgTxn, ChargeDefinition def, String narrationPrefix) {
        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(
                def.getGlFeeIncome(), DebitCredit.DEBIT,
                chgTxn.getBaseFee(),
                narrationPrefix + def.getChargeName()));
        if (chgTxn.getIgstAmount() != null && chgTxn.getIgstAmount().signum() > 0) {
            lines.add(new JournalLineRequest(
                    GLConstants.IGST_PAYABLE, DebitCredit.DEBIT,
                    chgTxn.getIgstAmount(),
                    narrationPrefix + "IGST"));
        } else {
            if (chgTxn.getCgstAmount() != null && chgTxn.getCgstAmount().signum() > 0) {
                lines.add(new JournalLineRequest(
                        GLConstants.CGST_PAYABLE, DebitCredit.DEBIT,
                        chgTxn.getCgstAmount(),
                        narrationPrefix + "CGST"));
            }
            if (chgTxn.getSgstAmount() != null && chgTxn.getSgstAmount().signum() > 0) {
                lines.add(new JournalLineRequest(
                        GLConstants.SGST_PAYABLE, DebitCredit.DEBIT,
                        chgTxn.getSgstAmount(),
                        narrationPrefix + "SGST"));
            }
        }
        return lines;
    }

    /** Load and validate tenant ownership of a ChargeTransaction. */
    private ChargeTransaction loadOwnedOrThrow(Long id, String tid) {
        return txnRepo.findById(id)
                .filter(c -> c.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CHARGE_NOT_FOUND", "" + id));
    }
}
