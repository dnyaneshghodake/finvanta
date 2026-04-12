package com.finvanta.charge;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.ChargeDefinition;
import com.finvanta.domain.entity.ChargeTransaction;
import com.finvanta.domain.enums.ChargeEventType;
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
 * CBS Charge/Fee Engine per Finacle CHG_ENGINE / Temenos FT.COMMISSION.
 *
 * Cross-cutting module for ALL CBS modules (Clearing, CASA, Loan).
 * Per RBI Fair Practices Code 2023 / GST Act 2017.
 *
 * GL Flow:
 *   DR Customer Account GL (2010/2020) — totalDebit
 *   CR Fee Income (4002) — baseFee
 *   CR CGST Payable (2200) — cgstAmount
 *   CR SGST Payable (2201) — sgstAmount
 */
@Service
public class ChargeEngine {

    private static final Logger log =
            LoggerFactory.getLogger(ChargeEngine.class);
    private static final BigDecimal CGST_RATE =
            new BigDecimal("0.09");
    private static final BigDecimal SGST_RATE =
            new BigDecimal("0.09");

    private final ChargeDefinitionRepository defRepo;
    private final ChargeTransactionRepository txnRepo;
    private final BranchRepository branchRepo;
    private final TransactionEngine txnEngine;
    private final BusinessDateService bizDateSvc;
    private final AuditService auditSvc;

    public ChargeEngine(
            ChargeDefinitionRepository defRepo,
            ChargeTransactionRepository txnRepo,
            BranchRepository branchRepo,
            TransactionEngine txnEngine,
            BusinessDateService bizDateSvc,
            AuditService auditSvc) {
        this.defRepo = defRepo;
        this.txnRepo = txnRepo;
        this.branchRepo = branchRepo;
        this.txnEngine = txnEngine;
        this.bizDateSvc = bizDateSvc;
        this.auditSvc = auditSvc;
    }

    /** Levy a charge. Returns null if no charge definition exists. */
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
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();

        ChargeDefinition def = resolveChargeDefinition(
                tid, eventType, productCode);
        if (def == null) return null;

        BigDecimal baseFee = computeBaseFee(def, txnAmount);
        if (baseFee.signum() <= 0) return null;

        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        if (def.isGstApplicable()) {
            cgst = baseFee.multiply(CGST_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
            sgst = baseFee.multiply(SGST_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalDebit = baseFee.add(cgst).add(sgst);

        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(
                customerGlCode, DebitCredit.DEBIT, totalDebit,
                def.getChargeName() + " charge"));
        lines.add(new JournalLineRequest(
                def.getGlFeeIncome(), DebitCredit.CREDIT,
                baseFee, def.getChargeName()));
        if (cgst.signum() > 0) {
            lines.add(new JournalLineRequest(
                    GLConstants.CGST_PAYABLE,
                    DebitCredit.CREDIT, cgst,
                    "CGST on " + def.getChargeName()));
            lines.add(new JournalLineRequest(
                    GLConstants.SGST_PAYABLE,
                    DebitCredit.CREDIT, sgst,
                    "SGST on " + def.getChargeName()));
        }

        TransactionResult result = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CHARGE")
                        .transactionType("CHG_"
                                + eventType.name())
                        .accountReference(accountNumber)
                        .amount(totalDebit)
                        .valueDate(bd)
                        .branchCode(branchCode)
                        .narration(def.getChargeName()
                                + " | " + sourceModule
                                + ":" + sourceRef)
                        .journalLines(lines)
                        .systemGenerated(true)
                        .initiatedBy("SYSTEM")
                        .build());

        Branch branch = branchRepo
                .findByTenantIdAndBranchCode(tid, branchCode)
                .orElse(null);
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
        chgTxn.setCgstAmount(cgst);
        chgTxn.setSgstAmount(sgst);
        chgTxn.setTotalDebit(totalDebit);
        chgTxn.setJournalEntryId(
                result.getJournalEntryId());
        chgTxn.setVoucherNumber(
                result.getVoucherNumber());
        chgTxn.setPostedAt(LocalDateTime.now());
        chgTxn.setCreatedBy("SYSTEM");
        txnRepo.save(chgTxn);

        auditSvc.logEvent("ChargeTransaction",
                chgTxn.getId(), "CHARGE_LEVIED",
                null, sourceRef, "CHARGE",
                eventType.name() + " | INR " + baseFee
                        + " + GST " + cgst.add(sgst)
                        + " = " + totalDebit
                        + " | " + accountNumber);
        log.info("Charge levied: event={}, acct={}, "
                + "fee={}, gst={}, total={}",
                eventType, accountNumber,
                baseFee, cgst.add(sgst), totalDebit);

        return new ChargeResult(def.getId(), baseFee,
                cgst, sgst, totalDebit,
                result.getJournalEntryId(),
                result.getVoucherNumber());
    }

    /** Waive a previously levied charge with contra GL. */
    @Transactional
    public ChargeTransaction waiveCharge(
            Long chargeTransactionId, String reason) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        if (reason == null || reason.isBlank())
            throw new BusinessException("REASON_REQUIRED",
                    "Waiver reason mandatory per RBI");
        ChargeTransaction chgTxn = txnRepo.findById(
                        chargeTransactionId)
                .filter(c -> c.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CHARGE_NOT_FOUND",
                        "" + chargeTransactionId));
        if (chgTxn.isWaived())
            throw new BusinessException("ALREADY_WAIVED",
                    "Charge already waived");
        ChargeDefinition def = chgTxn.getChargeDefinition();
        if (!def.isWaivable())
            throw new BusinessException("NOT_WAIVABLE",
                    def.getChargeName()
                            + " is not waivable per policy");

        List<JournalLineRequest> contraLines =
                new ArrayList<>();
        contraLines.add(new JournalLineRequest(
                def.getGlFeeIncome(), DebitCredit.DEBIT,
                chgTxn.getBaseFee(),
                "Waiver: " + def.getChargeName()));
        if (chgTxn.getCgstAmount().signum() > 0) {
            contraLines.add(new JournalLineRequest(
                    GLConstants.CGST_PAYABLE,
                    DebitCredit.DEBIT,
                    chgTxn.getCgstAmount(),
                    "CGST waiver"));
            contraLines.add(new JournalLineRequest(
                    GLConstants.SGST_PAYABLE,
                    DebitCredit.DEBIT,
                    chgTxn.getSgstAmount(),
                    "SGST waiver"));
        }
        contraLines.add(new JournalLineRequest(
                GLConstants.SB_DEPOSITS,
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
                .narration("Charge waiver: "
                        + def.getChargeName()
                        + " — " + reason)
                .journalLines(contraLines)
                .systemGenerated(false)
                .initiatedBy(user)
                .build());

        chgTxn.setWaived(true);
        chgTxn.setWaiverReason(reason);
        chgTxn.setWaivedBy(user);
        chgTxn.setUpdatedBy(user);
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

    /** Resolve charge definition: product-specific first, global fallback. */
    ChargeDefinition resolveChargeDefinition(
            String tenantId, ChargeEventType eventType,
            String productCode) {
        List<ChargeDefinition> defs =
                defRepo.findApplicableCharges(
                        tenantId, eventType, productCode);
        return defs.isEmpty() ? null : defs.get(0);
    }

    /** Compute base fee: FLAT returns amount, PERCENTAGE applies with min/max caps. */
    BigDecimal computeBaseFee(ChargeDefinition def,
            BigDecimal txnAmount) {
        if ("FLAT".equals(def.getChargeType())) {
            return def.getChargeAmount();
        }
        if ("PERCENTAGE".equals(def.getChargeType())) {
            BigDecimal fee = txnAmount
                    .multiply(def.getChargePercentage())
                    .divide(new BigDecimal("100"), 2,
                            RoundingMode.HALF_UP);
            if (def.getMinCharge() != null
                    && fee.compareTo(def.getMinCharge()) < 0)
                fee = def.getMinCharge();
            if (def.getMaxCharge() != null
                    && fee.compareTo(def.getMaxCharge()) > 0)
                fee = def.getMaxCharge();
            return fee;
        }
        return BigDecimal.ZERO;
    }
}
