package com.finvanta.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * CBS Test: BranchAwareUserDetails MFA flag per RBI IT Governance Direction 2023.
 *
 * Validates that the mfaRequired flag is correctly propagated through
 * the authentication pipeline for TOTP login redirect.
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
                "maker1", "hash", true, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        assertFalse(details.isMfaRequired());
    }

    @Test
    @DisplayName("Backward-compatible constructor (no MFA param) defaults to false")
    void backwardCompatConstructor_mfaDefault_false() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "maker1", "hash", true, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001");

        assertFalse(details.isMfaRequired());
    }

    @Test
    @DisplayName("Simple constructor defaults MFA to false")
    void simpleConstructor_mfaDefault_false() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "maker1", "hash",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001");

        assertFalse(details.isMfaRequired());
    }

    @Test
    @DisplayName("Locked account flag propagates correctly")
    void lockedAccount_flagPropagates() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "locked_user", "hash", false, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        assertFalse(details.isAccountNonLocked());
    }

    @Test
    @DisplayName("Expired credentials flag propagates correctly")
    void expiredCredentials_flagPropagates() {
        BranchAwareUserDetails details = new BranchAwareUserDetails(
                "expired_user", "hash", true, false,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MAKER")),
                1L, "HQ001", false);

        assertFalse(details.isCredentialsNonExpired());
    }
}
