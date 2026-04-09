package com.finvanta.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * CBS Test: BranchAwareUserDetails MFA + password expiry flags
 * per RBI IT Governance Direction 2023.
 *
 * Validates that mfaRequired and passwordExpired flags are correctly
 * propagated through the authentication pipeline.
 *
 * IMPORTANT: credentialsNonExpired is ALWAYS true to Spring Security.
 * Password expiry is handled by MfaAuthenticationSuccessHandler via
 * the custom isPasswordExpired() flag — NOT by Spring's built-in check.
 */
class BranchAwareUserDetailsTest {

    @Test
    @DisplayName("Full constructor with mfaRequired=true carries flag")
    void fullConstructor_mfaRequired_true() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "admin", "hash", true, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")),
                1L, "HQ001", true);

        assertTrue(details.isMfaRequired());
        assertEquals(1L, details.getBranchId());
        assertEquals("HQ001", details.getBranchCode());
    }

    @Test
    @DisplayName("Full constructor with mfaRequired=false")
    void fullConstructor_mfaRequired_false() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "maker1", "hash", true, false,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        assertFalse(details.isMfaRequired());
    }

    @Test
    @DisplayName("Backward-compatible constructor (no MFA param) defaults to false")
    void backwardCompatConstructor_mfaDefault_false() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "maker1", "hash", true, false,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001");

        assertFalse(details.isMfaRequired());
        assertFalse(details.isPasswordExpired());
    }

    @Test
    @DisplayName("Simple constructor defaults MFA and passwordExpired to false")
    void simpleConstructor_mfaDefault_false() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "maker1", "hash",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001");

        assertFalse(details.isMfaRequired());
        assertFalse(details.isPasswordExpired());
    }

    @Test
    @DisplayName("Locked account flag propagates correctly")
    void lockedAccount_flagPropagates() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "locked_user", "hash", false, false,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        assertFalse(details.isAccountNonLocked());
    }

    @Test
    @DisplayName("Password expired flag propagates correctly (Spring credentialsNonExpired stays true)")
    void passwordExpired_flagPropagates() {
        // passwordExpired=true means password IS expired
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "expired_user", "hash", true, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        // Our custom flag correctly reflects expiry
        assertTrue(details.isPasswordExpired());
        // Spring Security's credentialsNonExpired is ALWAYS true (we handle expiry ourselves)
        assertTrue(details.isCredentialsNonExpired());
    }

    @Test
    @DisplayName("Password not expired — both flags correct")
    void passwordNotExpired_flagCorrect() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "active_user", "hash", true, false,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        assertFalse(details.isPasswordExpired());
        assertTrue(details.isCredentialsNonExpired());
    }

    @Test
    @DisplayName("MFA required + password expired — both flags set correctly")
    void mfaRequired_passwordExpired_bothFlags() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "admin", "hash", true, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")),
                1L, "HQ001", true);

        assertTrue(details.isMfaRequired());
        assertTrue(details.isPasswordExpired());
        // Spring always allows login — we handle both MFA and expiry in success handler
        assertTrue(details.isCredentialsNonExpired());
    }
}
