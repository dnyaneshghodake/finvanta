package com.finvanta.service;

import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * CBS Transaction Limit Validation Service per Finacle/Temenos Internal Controls.
 *
 * Per RBI guidelines on internal controls and operational risk management:
 * - Every financial transaction must be validated against configured limits
 * - Limits cascade: type-specific → 'ALL' fallback → no limit (if unconfigured)
 * - Transactions exceeding limits are rejected with a clear error code
 *
 * Limit resolution order:
 * 1. Role + specific transaction type (e.g., MAKER + REPAYMENT)
 * 2. Role + ALL (e.g., MAKER + ALL)
 * 3. No limit configured → transaction proceeds (backward compatible)
 *
 * This service is called before any financial operation in the service layer.
 */
@Service
public class TransactionLimitService {

    private static final Logger log = LoggerFactory.getLogger(TransactionLimitService.class);

    private final TransactionLimitRepository limitRepository;

    public TransactionLimitService(TransactionLimitRepository limitRepository) {
        this.limitRepository = limitRepository;
    }

    /**
     * Validates a transaction amount against configured limits for the current user's role.
     *
     * @param amount          Transaction amount to validate
     * @param transactionType Transaction type (REPAYMENT, DISBURSEMENT, PREPAYMENT, etc.)
     * @throws BusinessException if amount exceeds configured limit
     */
    public void validateTransactionLimit(BigDecimal amount, String transactionType) {
        String tenantId = TenantContext.getCurrentTenant();
        String role = SecurityUtil.getCurrentUserRole();

        if (role == null) {
            log.debug("No role found for current user — skipping limit check");
            return;
        }

        // Resolve limit: type-specific first, then 'ALL' fallback
        TransactionLimit limit = limitRepository.findByRoleAndType(tenantId, role, transactionType)
            .or(() -> limitRepository.findByRoleForAllTypes(tenantId, role))
            .orElse(null);

        if (limit == null) {
            log.debug("No transaction limit configured for role={}, type={}", role, transactionType);
            return;
        }

        // Per-transaction limit check
        if (limit.getPerTransactionLimit() != null
                && amount.compareTo(limit.getPerTransactionLimit()) > 0) {
            throw new BusinessException("TRANSACTION_LIMIT_EXCEEDED",
                "Transaction amount ₹" + amount + " exceeds per-transaction limit of ₹"
                    + limit.getPerTransactionLimit() + " for role " + role
                    + ". Requires higher authority approval.");
        }

        log.debug("Transaction limit check passed: role={}, type={}, amount={}, limit={}",
            role, transactionType, amount, limit.getPerTransactionLimit());
    }
}
