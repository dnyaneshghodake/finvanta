package com.finvanta.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.enums.UserRole;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.BusinessException;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CBS Test: MFA Service per RBI IT Governance Direction 2023 Section 8.4.
 *
 * Validates MFA lifecycle: enable, enroll, verify, disable.
 * Per Finacle USER_MASTER MFA: all state transitions must be audited.
 */
@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private MfaService mfaService;

    private AppUser testUser;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setTenantId("DEFAULT");
        testUser.setUsername("testuser");
        testUser.setRole(UserRole.MAKER);
        testUser.setMfaEnabled(false);
        testUser.setMfaSecret(null);
        testUser.setMfaEnrolledDate(null);
    }

    @Test
    @DisplayName("enableMfa sets mfa_enabled=true and audits")
    void enableMfa_setsFlag() {
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        boolean result = mfaService.enableMfa("testuser");

        assertTrue(result);
        assertTrue(testUser.isMfaEnabled());
        verify(userRepository).save(testUser);
        verify(auditService).logEvent(eq("AppUser"), eq(1L), eq("MFA_ENABLED"),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("enableMfa returns false if already enabled (idempotent)")
    void enableMfa_alreadyEnabled_returnsFalse() {
        testUser.setMfaEnabled(true);
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        boolean result = mfaService.enableMfa("testuser");

        assertFalse(result);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("enrollMfa generates Base32 secret and persists")
    void enrollMfa_generatesSecret() {
        testUser.setMfaEnabled(true);
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        String secret = mfaService.enrollMfa("testuser");

        assertNotNull(secret);
        assertTrue(secret.length() >= 20, "Base32 secret should be at least 20 chars");
        // Verify only uppercase letters and digits 2-7 (Base32 alphabet)
        assertTrue(secret.matches("[A-Z2-7]+"), "Must be valid Base32");
        assertNotNull(testUser.getMfaSecret());
        verify(userRepository).save(testUser);
    }

    @Test
    @DisplayName("enrollMfa throws if MFA not enabled")
    void enrollMfa_notEnabled_throwsException() {
        testUser.setMfaEnabled(false);
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mfaService.enrollMfa("testuser"));
        assertEquals("MFA_NOT_ENABLED", ex.getErrorCode());
    }

    @Test
    @DisplayName("enrollMfa throws if already enrolled")
    void enrollMfa_alreadyEnrolled_throwsException() {
        testUser.setMfaEnabled(true);
        testUser.setMfaSecret("EXISTING_SECRET");
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mfaService.enrollMfa("testuser"));
        assertEquals("MFA_ALREADY_ENROLLED", ex.getErrorCode());
    }

    @Test
    @DisplayName("buildOtpAuthUri generates valid otpauth:// URI")
    void buildOtpAuthUri_validFormat() {
        String uri = mfaService.buildOtpAuthUri("testuser", "JBSWY3DPEHPK3PXP");

        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("testuser"));
        assertTrue(uri.contains("secret=JBSWY3DPEHPK3PXP"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    @Test
    @DisplayName("buildOtpAuthUri URL-encodes spaces and special chars per RFC 6238 key URI format")
    void buildOtpAuthUri_encodesSpecialChars() {
        String uri = mfaService.buildOtpAuthUri("user@bank.com", "JBSWY3DPEHPK3PXP");

        // Issuer "Finvanta CBS" must have space encoded in both label and query param
        assertTrue(uri.startsWith("otpauth://totp/Finvanta%20CBS:"),
                "Issuer space must be percent-encoded in label");
        assertTrue(uri.contains("&issuer=Finvanta%20CBS"),
                "Issuer space must be percent-encoded in query param");
        // Username @ must be encoded in the label path segment
        assertTrue(uri.contains("user%40bank.com"),
                "Username @ must be percent-encoded");
        // Must NOT contain raw unencoded space in the URI
        assertFalse(uri.contains("Finvanta CBS"),
                "Raw space must not appear in otpauth URI");
    }

    @Test
    @DisplayName("verifyLoginTotp returns true when MFA not enabled")
    void verifyLoginTotp_mfaDisabled_returnsTrue() {
        testUser.setMfaEnabled(false);
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        assertTrue(mfaService.verifyLoginTotp("testuser", "123456"));
    }

    @Test
    @DisplayName("verifyLoginTotp returns true when MFA enabled but no secret")
    void verifyLoginTotp_noSecret_returnsTrue() {
        testUser.setMfaEnabled(true);
        testUser.setMfaSecret(null);
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        assertTrue(mfaService.verifyLoginTotp("testuser", "123456"));
    }

    @Test
    @DisplayName("disableMfa clears all MFA fields")
    void disableMfa_clearsAllFields() {
        testUser.setMfaEnabled(true);
        testUser.setMfaSecret("SOME_SECRET");
        testUser.setMfaEnrolledDate(LocalDate.now());
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        mfaService.disableMfa("testuser", "User requested disable");

        assertFalse(testUser.isMfaEnabled());
        assertNull(testUser.getMfaSecret());
        assertNull(testUser.getMfaEnrolledDate());
        verify(userRepository).save(testUser);
        verify(auditService).logEvent(eq("AppUser"), eq(1L), eq("MFA_DISABLED"),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("disableMfa throws for ADMIN role per RBI mandate")
    void disableMfa_adminRole_throwsException() {
        testUser.setRole(UserRole.ADMIN);
        testUser.setMfaEnabled(true);
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mfaService.disableMfa("testuser", "Some reason"));
        assertEquals("MFA_MANDATORY_FOR_ADMIN", ex.getErrorCode());
    }

    @Test
    @DisplayName("disableMfa throws when reason is missing")
    void disableMfa_noReason_throwsException() {
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("testuser")))
                .thenReturn(Optional.of(testUser));

        assertThrows(BusinessException.class,
                () -> mfaService.disableMfa("testuser", ""));
        assertThrows(BusinessException.class,
                () -> mfaService.disableMfa("testuser", null));
    }

    @Test
    @DisplayName("User not found throws BusinessException")
    void anyOperation_userNotFound_throwsException() {
        when(userRepository.findByTenantIdAndUsername(anyString(), eq("unknown")))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> mfaService.enableMfa("unknown"));
        assertThrows(BusinessException.class, () -> mfaService.enrollMfa("unknown"));
        assertThrows(BusinessException.class, () -> mfaService.disableMfa("unknown", "reason"));
    }
}
