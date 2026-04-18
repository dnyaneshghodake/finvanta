package com.finvanta.service;

import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.repository.ApprovalWorkflowRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests MakerCheckerService ALWAYS_REQUIRE_APPROVAL for high-risk transaction types.
 *
 * Per RBI Internal Controls / Finacle TRAN_AUTH:
 * REVERSAL, WRITE_OFF, WRITE_OFF_RECOVERY must ALWAYS require dual authorization
 * regardless of amount — even INR 1 reversal needs checker approval.
 */
@ExtendWith(MockitoExtension.class)
class MakerCheckerAlwaysApprovalTest {

    @Mock
    private ApprovalWorkflowRepository workflowRepository;

    @Mock
    private TransactionLimitRepository limitRepository;

    private MakerCheckerService service;

    @BeforeEach
    void setUp() {
        service = new MakerCheckerService(workflowRepository, limitRepository);
        TenantContext.setCurrentTenant("TEST_TENANT");
        setSecurityContext("MAKER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(String role) {
        BranchAwareUserDetails user = new BranchAwareUserDetails(
                "testuser", "password",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)),
                1L, "BR001");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities()));
    }

    @Test
    @DisplayName("REVERSAL always requires approval — even for INR 1")
    void reversal_alwaysRequiresApproval() {
        assertTrue(service.requiresApproval(new BigDecimal("1.00"), "REVERSAL"),
                "REVERSAL of INR 1 must require approval per RBI Internal Controls");
    }

    @Test
    @DisplayName("WRITE_OFF always requires approval — even for INR 1")
    void writeOff_alwaysRequiresApproval() {
        assertTrue(service.requiresApproval(new BigDecimal("1.00"), "WRITE_OFF"),
                "WRITE_OFF of INR 1 must require approval per RBI Internal Controls");
    }

    @Test
    @DisplayName("WRITE_OFF_RECOVERY always requires approval — even for INR 1")
    void writeOffRecovery_alwaysRequiresApproval() {
        assertTrue(service.requiresApproval(new BigDecimal("1.00"), "WRITE_OFF_RECOVERY"),
                "WRITE_OFF_RECOVERY of INR 1 must require approval per RBI Internal Controls");
    }

    @Test
    @DisplayName("CASH_DEPOSIT below limit does NOT require approval")
    void cashDeposit_belowLimit_autoApproved() {
        // No limit configured — auto-approve
        when(limitRepository.findByRoleAndType(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(limitRepository.findByRoleForAllTypes(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertFalse(service.requiresApproval(new BigDecimal("50000.00"), "CASH_DEPOSIT"),
                "CASH_DEPOSIT below threshold should be auto-approved");
    }

    @Test
    @DisplayName("REVERSAL requires approval even when no limits are configured")
    void reversal_requiresApproval_evenWithNoLimits() {
        // Verify the method doesn't even check limits for REVERSAL
        assertTrue(service.requiresApproval(new BigDecimal("999999999.00"), "REVERSAL"));
        // limitRepository should NOT be called for ALWAYS_REQUIRE_APPROVAL types
        verifyNoInteractions(limitRepository);
    }
}