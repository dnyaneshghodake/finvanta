package com.finvanta.transaction;

import com.finvanta.domain.entity.Tenant;
import com.finvanta.repository.TenantRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS Tier-1 Transaction Pre-Validation Service per Finacle TRAN_VALIDATE / Temenos OFS.VALIDATE.
 *
 * <p>Centralizes all pre-financial validation checks that must run BEFORE any
 * GL posting. These checks were previously scattered across module-level code
 * (DepositAccountServiceImpl, LoanAccountServiceImpl) — meaning a new module
 * that forgot to implement them would bypass critical validations.
 *
 * <p><b>Checks performed:</b>
 * <ul>
 *   <li><b>Tenant validation:</b> Tenant active, license not expired</li>
 *   <li><b>RBI CTR threshold:</b> Cash transactions ≥ ₹10L flagged for reporting</li>
 *   <li><b>Large value flagging:</b> Transactions ≥ ₹50L flagged for enhanced monitoring</li>
 * </ul>
 *
 * <p><b>Design principle:</b> These checks run inside TransactionEngine.executeInternal()
 * AFTER idempotency (Step 1) and BEFORE business date validation (Step 2).
 * They are non-blocking for AML (flag, don't block) but blocking for tenant
 * validation (suspended tenant = hard reject).
 *
 * @see TransactionEngine
 */
@Service
public class TransactionValidationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionValidationService.class);

    /**
     * RBI PMLA 2002 / FIU-IND: Cash Transaction Report threshold.
     * All cash transactions (deposit or withdrawal) of ₹10,00,000 or more
     * must be reported to FIU-IND within 15 days.
     * Per RBI Master Direction on KYC 2016 §28(2).
     */
    private static final BigDecimal CTR_THRESHOLD = new BigDecimal("1000000.00");

    /**
     * RBI enhanced monitoring threshold for large-value transactions.
     * Transactions ≥ ₹50L require enhanced due diligence and are flagged
     * for risk monitoring. Per RBI Internal Controls guidelines.
     */
    private static final BigDecimal LARGE_VALUE_THRESHOLD = new BigDecimal("5000000.00");

    /** Cash transaction types that trigger CTR reporting per RBI PMLA */
    private static final java.util.Set<String> CASH_TXN_TYPES = java.util.Set.of(
            "CASH_DEPOSIT", "CASH_WITHDRAWAL");

    private final TenantRepository tenantRepository;

    public TransactionValidationService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Validates the tenant is active and licensed.
     * Per RBI IT Governance Direction 2023 §8.3: a suspended or unlicensed
     * tenant must not be able to post financial transactions.
     *
     * @throws BusinessException TENANT_SUSPENDED if tenant is inactive
     * @throws BusinessException TENANT_LICENSE_EXPIRED if license has expired
     */
    public void validateTenant() {
        String tenantCode = TenantContext.getCurrentTenant();
        Tenant tenant = tenantRepository.findByTenantCode(tenantCode).orElse(null);

        if (tenant == null) {
            // Tenant not found in DB — this can happen for the DEFAULT tenant in dev mode.
            // Log warning but don't block (backward compatibility with dev/test profiles).
            log.warn("Tenant validation skipped: tenant '{}' not found in tenants table", tenantCode);
            return;
        }

        if (!tenant.isActive()) {
            log.error("TENANT SUSPENDED — blocking all postings: tenant={}", tenantCode);
            throw new BusinessException(
                    "TENANT_SUSPENDED",
                    "Tenant " + tenantCode + " is suspended. All financial postings are blocked. "
                            + "Contact the system administrator.");
        }

        if (tenant.getLicenseExpiry() != null && LocalDate.now().isAfter(tenant.getLicenseExpiry())) {
            log.error("TENANT LICENSE EXPIRED — blocking all postings: tenant={}, expiry={}",
                    tenantCode, tenant.getLicenseExpiry());
            throw new BusinessException(
                    "TENANT_LICENSE_EXPIRED",
                    "Tenant " + tenantCode + " license expired on " + tenant.getLicenseExpiry()
                            + ". Renew the license to resume financial postings.");
        }
    }

    /**
     * CBS Tier-1: RBI CTR/STR Threshold Flagging per PMLA 2002 / FIU-IND.
     *
     * <p>Flags transactions that meet RBI reporting thresholds:
     * <ul>
     *   <li><b>CTR (Cash Transaction Report):</b> Cash transactions ≥ ₹10L
     *       must be reported to FIU-IND within 15 days per RBI KYC Master Direction §28(2)</li>
     *   <li><b>Large Value:</b> Any transaction ≥ ₹50L flagged for enhanced monitoring</li>
     * </ul>
     *
     * <p><b>Non-blocking:</b> This method flags but does NOT block the transaction.
     * Per RBI PMLA: the transaction must be processed; the report is filed afterward.
     * Blocking would tip off the customer (tipping-off is a PMLA offense).
     *
     * <p>The flags are returned as a bitmask for the caller to include in the
     * TransactionResult and audit trail.
     *
     * @param amount Transaction amount
     * @param transactionType Transaction type (CASH_DEPOSIT, CASH_WITHDRAWAL, etc.)
     * @param accountReference Account number for logging
     * @return RBI compliance flags (0 = no flags, bitwise OR of flag constants)
     */
    public int evaluateRbiComplianceFlags(BigDecimal amount, String transactionType, String accountReference) {
        int flags = 0;

        // CTR: Cash transactions ≥ ₹10L per RBI PMLA 2002
        if (CASH_TXN_TYPES.contains(transactionType) && amount.compareTo(CTR_THRESHOLD) >= 0) {
            flags |= RBI_FLAG_CTR;
            log.info("RBI CTR FLAG: {} of INR {} on account {} — reportable to FIU-IND within 15 days",
                    transactionType, amount, accountReference);
        }

        // Large value: Any transaction ≥ ₹50L
        if (amount.compareTo(LARGE_VALUE_THRESHOLD) >= 0) {
            flags |= RBI_FLAG_LARGE_VALUE;
            log.info("LARGE VALUE FLAG: {} of INR {} on account {} — enhanced monitoring required",
                    transactionType, amount, accountReference);
        }

        return flags;
    }

    /** RBI CTR (Cash Transaction Report) flag — transaction must be reported to FIU-IND */
    public static final int RBI_FLAG_CTR = 1;

    /** Large value transaction flag — requires enhanced due diligence */
    public static final int RBI_FLAG_LARGE_VALUE = 2;
}
