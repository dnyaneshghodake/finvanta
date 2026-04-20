package com.finvanta.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBS MfaRequiredException Tests — step-up authentication contract.
 *
 * Per RBI IT Governance Direction 2023 §8.3: the MFA step-up exception must
 * carry an opaque challengeId and channel so the Next.js BFF can prompt the
 * user and resume the original action. No PII in the exception fields.
 *
 * The errorCode MUST be "MFA_REQUIRED" so ApiExceptionHandler maps it to 428.
 */
class MfaRequiredExceptionTest {

    @Test
    @DisplayName("challengeId, channel, and message are preserved")
    void fieldsPreserved() {
        MfaRequiredException ex = new MfaRequiredException(
                "challenge-token-abc", "TOTP", "MFA step-up required");

        assertThat(ex.getChallengeId()).isEqualTo("challenge-token-abc");
        assertThat(ex.getChannel()).isEqualTo("TOTP");
        assertThat(ex.getMessage()).isEqualTo("MFA step-up required");
    }

    @Test
    @DisplayName("errorCode is always MFA_REQUIRED")
    void errorCodeIsMfaRequired() {
        MfaRequiredException ex = new MfaRequiredException(
                "challenge-123", "TOTP", "step-up");

        assertThat(ex.getErrorCode()).isEqualTo("MFA_REQUIRED");
    }

    @Test
    @DisplayName("MfaRequiredException extends BusinessException")
    void extendsBusinessException() {
        MfaRequiredException ex = new MfaRequiredException(
                "c", "TOTP", "msg");

        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
