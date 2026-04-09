package com.finvanta.domain.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.finvanta.domain.enums.UserRole;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CBS Test: AppUser MFA fields per RBI IT Governance Direction 2023 Section 8.4.
 *
 * Validates MFA enrollment detection logic used by the login gate
 * in CustomUserDetailsService.
 */
class AppUserMfaTest {

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setUsername("testuser");
        user.setRole(UserRole.MAKER);
    }

    @Test
    @DisplayName("isMfaEnrollmentRequired: enabled + no secret = true")
    void mfaEnabled_noSecret_enrollmentRequired() {
        user.setMfaEnabled(true);
        user.setMfaSecret(null);

        assertTrue(user.isMfaEnrollmentRequired());
    }

    @Test
    @DisplayName("isMfaEnrollmentRequired: enabled + blank secret = true")
    void mfaEnabled_blankSecret_enrollmentRequired() {
        user.setMfaEnabled(true);
        user.setMfaSecret("   ");

        assertTrue(user.isMfaEnrollmentRequired());
    }

    @Test
    @DisplayName("isMfaEnrollmentRequired: enabled + valid secret = false")
    void mfaEnabled_withSecret_enrollmentNotRequired() {
        user.setMfaEnabled(true);
        user.setMfaSecret("JBSWY3DPEHPK3PXP");

        assertFalse(user.isMfaEnrollmentRequired());
    }

    @Test
    @DisplayName("isMfaEnrollmentRequired: disabled = false regardless of secret")
    void mfaDisabled_enrollmentNotRequired() {
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        assertFalse(user.isMfaEnrollmentRequired());

        user.setMfaSecret("JBSWY3DPEHPK3PXP");
        assertFalse(user.isMfaEnrollmentRequired());
    }

    @Test
    @DisplayName("MFA fields default to disabled state")
    void defaultState_mfaDisabled() {
        AppUser newUser = new AppUser();

        assertFalse(newUser.isMfaEnabled());
        assertNull(newUser.getMfaSecret());
        assertNull(newUser.getMfaEnrolledDate());
        assertFalse(newUser.isMfaEnrollmentRequired());
    }
}
