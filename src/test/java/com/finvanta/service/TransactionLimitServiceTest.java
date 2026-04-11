package com.finvanta.service;

import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TransactionLimitService Tests per CBS Internal Controls.
 *
 * Validates per-role, per-transaction-type amount limit enforcement.
 * CBS: Daily aggregate now spans ALL modules (Loan + Deposit) per Finacle TRAN_AUTH.
 */
@ExtendWith(MockitoExtension.class)
class TransactionLimitServiceTest {

    @Mock
    private TransactionLimitRepository limitRepository;

    @Mock
    private LoanTransactionRepository loanTransactionRepository;

    @Mock
    private DepositTransactionRepository depositTransactionRepository;

    @InjectMocks
    private TransactionLimitService limitService;

    /** CBS business date used for all test cases (daily aggregate calculation) */
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 4, 1);

    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getCurrentTenant).thenReturn("DEFAULT");
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
        SecurityContextHolder.clearContext();
    }

    private void setCurrentUser(String username, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, "password", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Amount within limit passes validation")
    void amountWithinLimit_passes() {
        setCurrentUser("maker1", "MAKER");

        TransactionLimit limit = new TransactionLimit();
        limit.setPerTransactionLimit(new BigDecimal("1000000"));
        limit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "REPAYMENT")).thenReturn(Optional.of(limit));

        assertDoesNotThrow(
                () -> limitService.validateTransactionLimit(new BigDecimal("500000"), "REPAYMENT", BUSINESS_DATE));
    }

    @Test
    @DisplayName("Amount exceeding limit throws TRANSACTION_LIMIT_EXCEEDED")
    void amountExceedingLimit_throws() {
        setCurrentUser("maker1", "MAKER");

        TransactionLimit limit = new TransactionLimit();
        limit.setPerTransactionLimit(new BigDecimal("1000000"));
        limit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "REPAYMENT")).thenReturn(Optional.of(limit));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> limitService.validateTransactionLimit(new BigDecimal("1500000"), "REPAYMENT", BUSINESS_DATE));
        assertEquals("TRANSACTION_LIMIT_EXCEEDED", ex.getErrorCode());
    }

    @Test
    @DisplayName("No limit configured allows transaction (backward compatible)")
    void noLimitConfigured_allows() {
        setCurrentUser("maker1", "MAKER");

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "REPAYMENT")).thenReturn(Optional.empty());
        when(limitRepository.findByRoleForAllTypes("DEFAULT", "MAKER")).thenReturn(Optional.empty());

        assertDoesNotThrow(
                () -> limitService.validateTransactionLimit(new BigDecimal("99999999"), "REPAYMENT", BUSINESS_DATE));
    }

    @Test
    @DisplayName("Falls back to ALL type limit when specific type not configured")
    void fallbackToAllType() {
        setCurrentUser("maker1", "MAKER");

        TransactionLimit allLimit = new TransactionLimit();
        allLimit.setPerTransactionLimit(new BigDecimal("1000000"));
        allLimit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "PREPAYMENT"))
                .thenReturn(Optional.empty());
        when(limitRepository.findByRoleForAllTypes("DEFAULT", "MAKER")).thenReturn(Optional.of(allLimit));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> limitService.validateTransactionLimit(new BigDecimal("2000000"), "PREPAYMENT", BUSINESS_DATE));
        assertEquals("TRANSACTION_LIMIT_EXCEEDED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Zero limit blocks all transactions of that type")
    void zeroLimit_blocksAll() {
        setCurrentUser("maker1", "MAKER");

        TransactionLimit zeroLimit = new TransactionLimit();
        zeroLimit.setPerTransactionLimit(BigDecimal.ZERO);
        zeroLimit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "WRITE_OFF")).thenReturn(Optional.of(zeroLimit));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> limitService.validateTransactionLimit(new BigDecimal("1"), "WRITE_OFF", BUSINESS_DATE));
        assertEquals("TRANSACTION_LIMIT_EXCEEDED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Null per-transaction limit means unlimited")
    void nullLimit_meansUnlimited() {
        setCurrentUser("admin", "ADMIN");

        TransactionLimit unlimitedLimit = new TransactionLimit();
        unlimitedLimit.setPerTransactionLimit(null);
        unlimitedLimit.setDailyAggregateLimit(null);
        unlimitedLimit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "ADMIN", "REPAYMENT"))
                .thenReturn(Optional.of(unlimitedLimit));

        assertDoesNotThrow(
                () -> limitService.validateTransactionLimit(new BigDecimal("999999999"), "REPAYMENT", BUSINESS_DATE));
    }

    @Test
    @DisplayName("Daily aggregate limit exceeded throws DAILY_LIMIT_EXCEEDED (cross-module)")
    void dailyAggregateExceeded_throws() {
        setCurrentUser("maker1", "MAKER");

        TransactionLimit limit = new TransactionLimit();
        limit.setPerTransactionLimit(new BigDecimal("1000000"));
        limit.setDailyAggregateLimit(new BigDecimal("5000000"));
        limit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "REPAYMENT")).thenReturn(Optional.of(limit));
        // CBS: Daily aggregate now spans BOTH loan + deposit modules per Finacle TRAN_AUTH.
        // User processed ₹30L in loans + ₹15L in deposits = ₹45L total today.
        when(loanTransactionRepository.sumDailyAmountByUser(eq("DEFAULT"), eq("maker1"), eq(BUSINESS_DATE)))
                .thenReturn(new BigDecimal("3000000"));
        when(depositTransactionRepository.sumDailyAmountByUser(eq("DEFAULT"), eq("maker1"), eq(BUSINESS_DATE)))
                .thenReturn(new BigDecimal("1500000"));

        // This ₹6L transaction would push total to ₹51L, exceeding ₹50L daily limit
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> limitService.validateTransactionLimit(new BigDecimal("600000"), "REPAYMENT", BUSINESS_DATE));
        assertEquals("DAILY_LIMIT_EXCEEDED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Daily aggregate within limit passes (cross-module)")
    void dailyAggregateWithinLimit_passes() {
        setCurrentUser("maker1", "MAKER");

        TransactionLimit limit = new TransactionLimit();
        limit.setPerTransactionLimit(new BigDecimal("1000000"));
        limit.setDailyAggregateLimit(new BigDecimal("5000000"));
        limit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "REPAYMENT")).thenReturn(Optional.of(limit));
        // CBS: Cross-module aggregate: ₹20L loans + ₹10L deposits = ₹30L total today.
        when(loanTransactionRepository.sumDailyAmountByUser(eq("DEFAULT"), eq("maker1"), eq(BUSINESS_DATE)))
                .thenReturn(new BigDecimal("2000000"));
        when(depositTransactionRepository.sumDailyAmountByUser(eq("DEFAULT"), eq("maker1"), eq(BUSINESS_DATE)))
                .thenReturn(new BigDecimal("1000000"));

        // This ₹5L transaction would push total to ₹35L, within ₹50L daily limit
        assertDoesNotThrow(
                () -> limitService.validateTransactionLimit(new BigDecimal("500000"), "REPAYMENT", BUSINESS_DATE));
    }

    @Test
    @DisplayName("Daily aggregate catches cross-module bypass attempt")
    void dailyAggregate_crossModuleBypass_detected() {
        // CBS CRITICAL: Without cross-module aggregation, a MAKER could process
        // their full limit in loans AND deposits independently — effectively 2x the limit.
        setCurrentUser("maker1", "MAKER");

        TransactionLimit limit = new TransactionLimit();
        limit.setPerTransactionLimit(new BigDecimal("1000000"));
        limit.setDailyAggregateLimit(new BigDecimal("5000000"));
        limit.setActive(true);

        when(limitRepository.findByRoleAndType("DEFAULT", "MAKER", "CASH_DEPOSIT")).thenReturn(Optional.of(limit));
        // User processed ₹0 in loans but ₹48L in deposits today
        when(loanTransactionRepository.sumDailyAmountByUser(eq("DEFAULT"), eq("maker1"), eq(BUSINESS_DATE)))
                .thenReturn(BigDecimal.ZERO);
        when(depositTransactionRepository.sumDailyAmountByUser(eq("DEFAULT"), eq("maker1"), eq(BUSINESS_DATE)))
                .thenReturn(new BigDecimal("4800000"));

        // This ₹3L deposit would push CROSS-MODULE total to ₹51L — must be rejected
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> limitService.validateTransactionLimit(new BigDecimal("300000"), "CASH_DEPOSIT", BUSINESS_DATE));
        assertEquals("DAILY_LIMIT_EXCEEDED", ex.getErrorCode());
    }
}
