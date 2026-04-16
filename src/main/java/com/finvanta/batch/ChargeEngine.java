package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ChargeConfig;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.ChargeConfigRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Loan Charges Engine per Finacle CHRG_MASTER / Temenos AA.CHARGE.
 *
 * <p><b>Legacy loan-specific fee engine.</b> Retained only for the existing
 * loan fee posting path (processing fee, stamp duty, documentation, late-payment
 * fees) which uses the {@code ChargeConfig} slab-based model.
 *
 * <p>For cross-cutting charges (Clearing, CASA, and the unified charge API),
 * use {@link com.finvanta.charge.ChargeKernel} which consolidates levy / waive /
 * <b>symmetric reverse</b> with proper CGST/SGST/IGST split per GST Act 2017 §8.
 * The loan path will migrate to the kernel model once {@code ChargeConfig} is
 * folded into {@code ChargeDefinition}; until then this engine adds its own
 * {@link #reverseCharge} for symmetric RBI FPC 2023 §5.7 compliance so that no
 * loan fee posting lacks a reversal primitive (audit finding H6).
 *
 * <p>Charge Types:
 * <ul>
 *   <li>FLAT       -- Fixed amount (e.g., INR 500)</li>
 *   <li>PERCENTAGE -- % of base (e.g., 1% of loan amount)</li>
 *   <li>SLAB       -- Tiered rates (e.g., STAMP_DUTY varies by loan amount)</li>
 * </ul>
 *
 * <p>GST Handling (per RBI Integrated GST guidelines, loan path):
 * if charge is GST applicable, a 3-leg journal is posted:
 * <pre>
 *   DR Customer/Bank Ops (1100)
 *   CR Charge Income (product-specific or global GL code)
 *   CR GST Payable (2200 default, single head)
 * </pre>
 * The kernel model {@link com.finvanta.charge.ChargeKernel} supersedes this with
 * the full CGST/SGST/IGST split.
 */
@Deprecated(since = "2026-04", forRemoval = false)
@Service
public class ChargeEngine {

    private static final Logger log = LoggerFactory.getLogger(ChargeEngine.class);

    private final ChargeConfigRepository configRepository;
    private final LoanAccountRepository accountRepository;
    private final TransactionEngine transactionEngine;
    private final ProductGLResolver glResolver;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ChargeEngine(
            ChargeConfigRepository configRepository,
            LoanAccountRepository accountRepository,
            TransactionEngine transactionEngine,
            ProductGLResolver glResolver,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.accountRepository = accountRepository;
        this.transactionEngine = transactionEngine;
        this.glResolver = glResolver;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Calculate charge amount (excluding GST).
     *
     * @param chargeCode    Charge config identifier
     * @param baseAmount    Base amount (e.g., loan amount for processing fee)
     * @param productCode   Product code (for product-specific overrides)
     * @return ChargeResult with charge, GST, total, and GL codes
     */
    public ChargeResult calculateCharge(String chargeCode, BigDecimal baseAmount, String productCode) {
        String tenantId = TenantContext.getCurrentTenant();

        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_CHARGE_BASE", "Charge base amount must be positive");
        }

        ChargeConfig config = configRepository
                .findByTenantAndChargeCodeAndProduct(tenantId, chargeCode, productCode)
                .orElseThrow(() -> new BusinessException(
                        "CHARGE_CONFIG_NOT_FOUND",
                        "Charge configuration not found: " + chargeCode + " for product " + productCode));

        if (!config.getIsActive()) {
            throw new BusinessException("CHARGE_INACTIVE", "Charge configuration is inactive: " + chargeCode);
        }

        BigDecimal chargeAmount;

        // CBS: Calculate charge amount based on type
        if ("FLAT".equals(config.getCalculationType())) {
            chargeAmount = config.getBaseAmount();
        } else if ("PERCENTAGE".equals(config.getCalculationType())) {
            // Charge = Base * Percentage / 100
            chargeAmount = baseAmount
                    .multiply(config.getPercentage())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else if ("SLAB".equals(config.getCalculationType())) {
            chargeAmount = calculateSlabCharge(baseAmount, config.getSlabJson());
        } else {
            throw new BusinessException(
                    "UNSUPPORTED_CALCULATION_TYPE", "Unsupported calculation type: " + config.getCalculationType());
        }

        // CBS: Apply min/max bounds
        if (config.getMinAmount() != null && chargeAmount.compareTo(config.getMinAmount()) < 0) {
            chargeAmount = config.getMinAmount();
        }
        if (config.getMaxAmount() != null && chargeAmount.compareTo(config.getMaxAmount()) > 0) {
            chargeAmount = config.getMaxAmount();
        }

        // CBS: Calculate GST if applicable
        BigDecimal gstAmount = BigDecimal.ZERO;
        if (config.getGstApplicable() != null && config.getGstApplicable()) {
            BigDecimal gstRate = config.getGstRate() != null ? config.getGstRate() : BigDecimal.valueOf(18);
            gstAmount = chargeAmount.multiply(gstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        BigDecimal totalAmount = chargeAmount.add(gstAmount);

        log.info(
                "Charge calculated: chargeCode={}, base={}, charge={}, gst={}, total={}",
                chargeCode,
                baseAmount,
                chargeAmount,
                gstAmount,
                totalAmount);

        return new ChargeResult(
                chargeCode, chargeAmount, gstAmount, totalAmount, config.getGlChargeIncome(), config.getGlGstPayable());
    }

    /**
     * Apply charge to account (post via TransactionEngine).
     * Creates a 3-leg journal entry with charge and GST (if applicable).
     *
     * @param accountNumber Loan account number
     * @param chargeCode    Charge config identifier
     * @param baseAmount    Base amount
     * @param businessDate  CBS business date
     * @return ChargeResult with the actual calculated charge, GST, and total amounts
     */
    @Transactional
    public ChargeResult applyCharge(
            String accountNumber, String chargeCode, BigDecimal baseAmount, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        // CBS: Calculate charge (resolves product-specific config)
        ChargeResult result = calculateCharge(chargeCode, baseAmount, account.getProductType());

        // CBS: Resolve GL codes (product-specific or global fallback via ProductGLResolver)
        String glChargeIncome = result.glChargeIncome() != null
                ? result.glChargeIncome()
                : glResolver.getFeeIncomeGL(account.getProductType()); // Fallback to product GL

        String glGstPayable =
                result.glGstPayable() != null ? result.glGstPayable() : "2200"; // Fallback to default CGST Payable

        // CBS: Build 3-leg journal entry
        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(
                "1100", // Bank Operations — DR (customer pays)
                DebitCredit.DEBIT,
                result.totalAmount(),
                "Charge posting: " + chargeCode));
        lines.add(new JournalLineRequest(
                glChargeIncome, // Charge Income — CR
                DebitCredit.CREDIT,
                result.chargeAmount(),
                "Charge income: " + chargeCode));

        if (result.gstAmount().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalLineRequest(
                    glGstPayable, // GST Payable — CR
                    DebitCredit.CREDIT,
                    result.gstAmount(),
                    "GST on charge: " + chargeCode));
        }

        // CBS: Post via TransactionEngine (all financial postings route through engine)
        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("CHARGES")
                .transactionType("CHARGE_" + chargeCode)
                .accountReference(accountNumber)
                .amount(result.totalAmount())
                .valueDate(businessDate)
                .branchCode(account.getBranch() != null ? account.getBranch().getBranchCode() : null)
                .productType(account.getProductType())
                .narration("Charge: " + chargeCode + " on account " + accountNumber)
                .journalLines(lines)
                .systemGenerated(true) // System-initiated: charge application is an operational
                // action by bank staff, not a customer-initiated financial transaction.
                // Per Finacle CHRG_MASTER: charge posting must succeed atomically —
                // if it went through maker-checker, the LoanTransaction record in
                // chargeFee() would reference an unposted GL entry, causing
                // GL vs subledger reconciliation mismatch.
                .initiatedBy("SYSTEM")
                .build());

        // CBS: Audit trail
        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "CHARGE_APPLIED",
                null,
                chargeCode + " | " + result.totalAmount(),
                "CHARGES",
                "Charge applied: " + chargeCode + " (INR " + result.totalAmount() + "), Journal: "
                        + txnResult.getJournalRef());

        log.info(
                "Charge applied: account={}, chargeCode={}, amount={}, journal={}, voucher={}",
                accountNumber,
                chargeCode,
                result.totalAmount(),
                txnResult.getJournalRef(),
                txnResult.getVoucherNumber());

        return result;
    }

    /**
     * Reverse a previously applied loan charge (symmetric mirror-image contra).
     *
     * <p>Per RBI Fair Practices Code 2023 §5.7 / Finacle CHRG_MASTER: every
     * charge posting must have a reversal primitive. Prior to this method the
     * loan charge flow was levy-only, which violated the symmetry requirement
     * (audit finding H6). The cross-module flow already has
     * {@link com.finvanta.charge.ChargeKernel#reverseCharge} against persisted
     * {@code ChargeTransaction} rows; the loan flow does not persist a
     * {@code ChargeTransaction} (only a LoanTransaction + GL journal), so the
     * caller must provide the original amount breakdown.
     *
     * <p>The contra journal is the exact mirror image of {@link #applyCharge}:
     * <pre>
     *   DR Charge Income (glChargeIncome)   chargeAmount
     *   DR GST Payable   (glGstPayable)     gstAmount   (if non-zero)
     *   CR Bank Operations (1100)           totalAmount
     * </pre>
     * This guarantees the reversed net GL impact is zero and the trial balance
     * remains clean per double-entry accounting principles.
     *
     * @param accountNumber     loan account of the original charge
     * @param chargeCode        charge config identifier (used to resolve fallback GLs)
     * @param chargeAmount      original base fee amount (before GST)
     * @param gstAmount         original GST amount (0 if not GST-applicable)
     * @param originalJournalRef journal ref of the original levy (for audit linkage)
     * @param reason            operational reason -- mandatory per RBI FPC 2023
     * @param businessDate      CBS business date for the reversal posting
     * @return {@link TransactionResult} for the contra journal posting
     */
    @Transactional
    public TransactionResult reverseCharge(
            String accountNumber,
            String chargeCode,
            BigDecimal chargeAmount,
            BigDecimal gstAmount,
            String originalJournalRef,
            String reason,
            LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    "REASON_REQUIRED",
                    "Reversal reason mandatory per RBI Fair Practices Code 2023");
        }
        if (chargeAmount == null || chargeAmount.signum() <= 0) {
            throw new BusinessException(
                    "INVALID_CHARGE_AMOUNT",
                    "Original charge amount must be positive for reversal");
        }
        BigDecimal gst = gstAmount != null ? gstAmount : BigDecimal.ZERO;
        BigDecimal totalAmount = chargeAmount.add(gst);

        LoanAccount account = accountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND",
                        "Loan account not found: " + accountNumber));

        // Resolve GLs identically to applyCharge() so the reversal uses the same
        // heads. Falls back to the same product-GL / default-GST GLs.
        String glChargeIncome = glResolver.getFeeIncomeGL(account.getProductType());
        String glGstPayable = "2200";

        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(
                glChargeIncome,
                DebitCredit.DEBIT,
                chargeAmount,
                "Reversal of " + chargeCode));
        if (gst.signum() > 0) {
            lines.add(new JournalLineRequest(
                    glGstPayable,
                    DebitCredit.DEBIT,
                    gst,
                    "GST reversal for " + chargeCode));
        }
        lines.add(new JournalLineRequest(
                "1100",
                DebitCredit.CREDIT,
                totalAmount,
                "Charge reversal credit: " + chargeCode));

        TransactionResult txnResult = transactionEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CHARGES")
                        .transactionType("CHARGE_REVERSAL_" + chargeCode)
                        .accountReference(accountNumber)
                        .amount(totalAmount)
                        .valueDate(businessDate)
                        .branchCode(
                                account.getBranch() != null
                                        ? account.getBranch().getBranchCode()
                                        : null)
                        .productType(account.getProductType())
                        .narration(
                                "Reversal: " + chargeCode
                                        + " on " + accountNumber
                                        + " -- " + reason)
                        .journalLines(lines)
                        .systemGenerated(false)
                        .initiatedBy("SYSTEM")
                        .build());

        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "CHARGE_REVERSED",
                chargeCode + " | " + totalAmount,
                "REVERSED",
                "CHARGES",
                "Charge reversed: " + chargeCode
                        + " (INR " + totalAmount + "), originalRef="
                        + originalJournalRef
                        + ", reversalJournal=" + txnResult.getJournalRef()
                        + ", reason=" + reason);

        log.info(
                "Loan charge reversed: account={}, chargeCode={}, amount={},"
                        + " originalRef={}, reversalJournal={}, voucher={}",
                accountNumber,
                chargeCode,
                totalAmount,
                originalJournalRef,
                txnResult.getJournalRef(),
                txnResult.getVoucherNumber());

        return txnResult;
    }

    /**
     * Calculate slab-based charge from JSON slab configuration.
     * Slab JSON format: [{"min":0,"max":100000,"rate":0.5},{"min":100001,"max":500000,"rate":0.75}]
     *
     * @param amount  Amount to match against slabs
     * @param slabJson JSON array of slab definitions
     * @return Calculated charge amount
     */
    private BigDecimal calculateSlabCharge(BigDecimal amount, String slabJson) {
        try {
            List<Map<String, Object>> slabs = objectMapper.readValue(
                    slabJson, objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> slab : slabs) {
                BigDecimal min = new BigDecimal(slab.get("min").toString());
                BigDecimal max = new BigDecimal(slab.get("max").toString());
                BigDecimal rate = new BigDecimal(slab.get("rate").toString());

                if (amount.compareTo(min) >= 0 && amount.compareTo(max) <= 0) {
                    return amount.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
            }

            throw new BusinessException("SLAB_NOT_MATCHED", "No slab matches amount: " + amount);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("SLAB_PARSE_ERROR", "Error parsing slab JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Charge calculation result record.
     */
    public record ChargeResult(
            String chargeCode,
            BigDecimal chargeAmount,
            BigDecimal gstAmount,
            BigDecimal totalAmount,
            String glChargeIncome,
            String glGstPayable) {}
}
