package com.finvanta.service;

import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS Transaction Limit Validation Service per Finacle/Temenos Internal Controls.
 *
 * Per RBI guidelines on internal controls and operational risk management:
 * - Every financial transaction must be validated against configured limits
 * - Limits cascade: type-specific → 'ALL' fallback → no limit (if unconfigured)
 * - Transactions exceeding limits are rejected with a clear error code
 *
 * Two-level validation:
 * 1. Per-transaction limit: single transaction amount cap
 * 2. Daily aggregate limit: cumulative amount cap per user per business day
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
    private final LoanTransactionRepository transactionRepository;

    public TransactionLimitService(
            TransactionLimitRepository limitRepository, LoanTransactionRepository transactionRepository) {
        this.limitRepository = limitRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Validates a transaction amount against configured limits for the current user's role.
     * Checks both per-transaction and daily aggregate limits.
     *
     * CBS MANDATE: Always pass the business date explicitly. Using LocalDate.now() would
     * cause incorrect daily aggregate calculation during EOD (which runs after midnight).
     * All callers must obtain the business date from BusinessDateService or from the
     * TransactionRequest.valueDate (which is already validated by TransactionEngine Step 2).
     *
     * Per RBI Internal Controls Guidelines and Finacle TRAN_AUTH:
     * - Users with a transactional role (MAKER/CHECKER/ADMIN) are validated against limits
     * - Users with NO transactional role (e.g., AUDITOR-only) are rejected outright
     * - System-generated transactions (EOD) bypass this check entirely (caller's responsibility)
     *
     * The null-role rejection prevents a misconfigured user (no MAKER/CHECKER/ADMIN role)
     * from processing unlimited financial transactions. Per RBI segregation of duties,
     * every user-initiated financial operation must be traceable to a role with defined limits.
     */
    public void validateTransactionLimit(BigDecimal amount, String transactionType, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String role = SecurityUtil.getCurrentUserRole();

        if (role == null) {
            // CBS: No transactional role found. This means the user has no MAKER/CHECKER/ADMIN
            // role assigned. Per RBI Internal Controls, user-initiated financial transactions
            // require a role with defined limits. Reject to prevent unlimited access.
            String username = SecurityUtil.getCurrentUsername();
            log.warn(
                    "LIMIT_CHECK: No transactional role for user={}, type={}. "
                            + "User must have MAKER, CHECKER, or ADMIN role to initiate financial transactions.",
                    username,
                    transactionType);
            throw new BusinessException(
                    "NO_TRANSACTIONAL_ROLE",
                    "User " + username + " does not have a transactional role (MAKER/CHECKER/ADMIN). "
                            + "Financial transactions require an authorized role with configured limits.");
        }

        // Resolve limit: type-specific first, then 'ALL' fallback
        TransactionLimit limit = limitRepository
                .findByRoleAndType(tenantId, role, transactionType)
                .or(() -> limitRepository.findByRoleForAllTypes(tenantId, role))
                .orElse(null);

        if (limit == null) {
            log.debug("No transaction limit configured for role={}, type={}", role, transactionType);
            return;
        }

        // Per-transaction limit check
        if (limit.getPerTransactionLimit() != null && amount.compareTo(limit.getPerTransactionLimit()) > 0) {
            throw new BusinessException(
                    "TRANSACTION_LIMIT_EXCEEDED",
                    "Transaction amount INR " + amount + " exceeds per-transaction limit of INR "
                            + limit.getPerTransactionLimit() + " for role " + role
                            + ". Requires higher authority approval.");
        }

        // Daily aggregate limit check
        // Per Finacle/Temenos: sum of all transactions by this user today + proposed amount
        if (limit.getDailyAggregateLimit() != null) {
            String username = SecurityUtil.getCurrentUsername();
            BigDecimal todayTotal = transactionRepository.sumDailyAmountByUser(tenantId, username, businessDate);
            BigDecimal projectedTotal = todayTotal.add(amount);

            if (projectedTotal.compareTo(limit.getDailyAggregateLimit()) > 0) {
                throw new BusinessException(
                        "DAILY_LIMIT_EXCEEDED",
                        "Daily aggregate INR " + projectedTotal + " (existing INR " + todayTotal
                                + " + this INR " + amount + ") exceeds daily limit of INR "
                                + limit.getDailyAggregateLimit() + " for role " + role + ".");
            }
        }

        log.debug(
                "Transaction limit check passed: role={}, type={}, amount={}, perTxn={}, dailyAgg={}",
                role,
                transactionType,
                amount,
                limit.getPerTransactionLimit(),
                limit.getDailyAggregateLimit());
    }
}
