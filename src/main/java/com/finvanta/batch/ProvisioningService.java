package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * CBS Provisioning Engine per RBI IRAC Master Circular.
 *
 * RBI mandated provisioning percentages (on outstanding principal):
 *   Standard (ACTIVE/SMA):     0.40%
 *   Sub-Standard (secured):   15.00%
 *   Sub-Standard (unsecured): 25.00%
 *   Doubtful (up to 1 year):  25.00%
 *   Doubtful (1-3 years):     40.00%
 *   Doubtful (over 3 years): 100.00%
 *   Loss:                    100.00%
 *
 * Provisioning is calculated daily during EOD and the DELTA (difference between
 * required and existing provision) is posted to GL:
 *   Increase: DR Provision Expense (5001) / CR Provision for NPA (1003)
 *   Decrease: DR Provision for NPA (1003) / CR Provision Expense (5001)
 *
 * Per Finacle/Temenos: provisioning is account-level, not portfolio-level.
 * Each account tracks its own provisioningAmount field.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.40");
    /** RBI CDR: Restructured accounts carry 5% provisioning for first 2 years post-restructuring */
    private static final BigDecimal RESTRUCTURED_RATE = new BigDecimal("5.00");
    private static final BigDecimal SUB_STANDARD_SECURED_RATE = new BigDecimal("15.00");
    private static final BigDecimal SUB_STANDARD_UNSECURED_RATE = new BigDecimal("25.00");
    private static final BigDecimal DOUBTFUL_1YR_RATE = new BigDecimal("25.00");
    private static final BigDecimal DOUBTFUL_3YR_RATE = new BigDecimal("40.00");
    private static final BigDecimal DOUBTFUL_OVER_3YR_RATE = new BigDecimal("100.00");
    private static final BigDecimal LOSS_RATE = new BigDecimal("100.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LoanAccountRepository accountRepository;
    private final TransactionEngine transactionEngine;
    private final ProductGLResolver glResolver;
    private final AuditService auditService;

    public ProvisioningService(LoanAccountRepository accountRepository,
                                TransactionEngine transactionEngine,
                                ProductGLResolver glResolver,
                                AuditService auditService) {
        this.accountRepository = accountRepository;
        this.transactionEngine = transactionEngine;
        this.glResolver = glResolver;
        this.auditService = auditService;
    }

    /**
     * Calculates and posts provisioning for all active accounts.
     * Called by EodOrchestrator during Step 6.
     */
    @Transactional
    public void calculateAndPostProvisioning(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        List<LoanAccount> accounts = accountRepository.findAllActiveAccounts(tenantId);

        int processed = 0;
        int posted = 0;

        for (LoanAccount account : accounts) {
            try {
                BigDecimal required = calculateRequiredProvision(account, businessDate);
                BigDecimal existing = account.getProvisioningAmount();
                BigDecimal delta = required.subtract(existing);

                if (delta.abs().compareTo(new BigDecimal("0.01")) < 0) {
                    // No material change -- skip GL posting
                    continue;
                }

                if (delta.compareTo(BigDecimal.ZERO) > 0) {
                    // Increase provision: DR Expense / CR Provision
                    postProvisionChange(account, delta, businessDate, "PROVISION_INCREASE",
                        "Provision increase for " + account.getAccountNumber());
                } else {
                    // Decrease provision: DR Provision / CR Expense (reversal)
                    postProvisionChange(account, delta.abs(), businessDate, "PROVISION_DECREASE",
                        "Provision decrease for " + account.getAccountNumber());
                }

                account.setProvisioningAmount(required);
                account.setUpdatedBy("SYSTEM");
                accountRepository.save(account);
                posted++;

            } catch (Exception e) {
                log.error("Provisioning failed for {}: {}",
                    account.getAccountNumber(), e.getMessage());
            }
            processed++;
        }

        log.info("Provisioning complete: processed={}, posted={}", processed, posted);
    }

    /**
     * Calculates the required provision amount per RBI IRAC norms.
     */
    public BigDecimal calculateRequiredProvision(LoanAccount account, LocalDate businessDate) {
        BigDecimal outstanding = account.getOutstandingPrincipal();
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal rate = getProvisioningRate(account, businessDate);
        return outstanding.multiply(rate)
            .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the RBI IRAC provisioning rate based on asset classification.
     * Uses collateral reference as a proxy for secured/unsecured distinction.
     */
    public BigDecimal getProvisioningRate(LoanAccount account, LocalDate businessDate) {
        LoanStatus status = account.getStatus();
        boolean isSecured = account.getCollateralReference() != null
            && !account.getCollateralReference().isBlank();

        return switch (status) {
            case ACTIVE, SMA_0, SMA_1, SMA_2 -> STANDARD_RATE;
            case RESTRUCTURED -> RESTRUCTURED_RATE;
            case NPA_SUBSTANDARD -> isSecured
                ? SUB_STANDARD_SECURED_RATE : SUB_STANDARD_UNSECURED_RATE;
            case NPA_DOUBTFUL -> getDoubtfulRate(account, businessDate);
            case NPA_LOSS -> LOSS_RATE;
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal getDoubtfulRate(LoanAccount account, LocalDate businessDate) {
        if (account.getNpaDate() == null) {
            return DOUBTFUL_1YR_RATE;
        }
        long monthsInNpa = java.time.temporal.ChronoUnit.MONTHS.between(
            account.getNpaDate(), businessDate);
        if (monthsInNpa <= 12) {
            return DOUBTFUL_1YR_RATE;
        } else if (monthsInNpa <= 36) {
            return DOUBTFUL_3YR_RATE;
        } else {
            return DOUBTFUL_OVER_3YR_RATE;
        }
    }

    private void postProvisionChange(LoanAccount account, BigDecimal amount,
                                      LocalDate businessDate, String txnType,
                                      String narration) {
        String productType = account.getProductType();

        List<JournalLineRequest> lines;
        if ("PROVISION_INCREASE".equals(txnType)) {
            lines = List.of(
                new JournalLineRequest(glResolver.getProvisionExpenseGL(productType),
                    DebitCredit.DEBIT, amount,
                    "Provision expense - " + account.getAccountNumber()),
                new JournalLineRequest(glResolver.getProvisionNpaGL(productType),
                    DebitCredit.CREDIT, amount,
                    "Provision for NPA - " + account.getAccountNumber())
            );
        } else {
            lines = List.of(
                new JournalLineRequest(glResolver.getProvisionNpaGL(productType),
                    DebitCredit.DEBIT, amount,
                    "Provision release - " + account.getAccountNumber()),
                new JournalLineRequest(glResolver.getProvisionExpenseGL(productType),
                    DebitCredit.CREDIT, amount,
                    "Provision expense reversal - " + account.getAccountNumber())
            );
        }

        transactionEngine.execute(
            TransactionRequest.builder()
                .sourceModule("PROVISIONING")
                .transactionType(txnType)
                .accountReference(account.getAccountNumber())
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(account.getBranch() != null
                    ? account.getBranch().getBranchCode() : null)
                .productType(productType)
                .narration(narration)
                .journalLines(lines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build()
        );
    }
}
