package com.finvanta.util;

/**
 * CBS Step-Up Authentication Exception per RBI IT Governance Direction 2023 §8.3
 * and NPCI/UPI risk-based authentication guidelines.
 *
 * <p>Thrown by service-layer code when a sensitive operation (high-value transfer,
 * beneficiary add, limit change, after-hours posting) requires the user to
 * re-assert MFA even though they are already session-authenticated.
 *
 * <p>Mapped by {@link com.finvanta.api.ApiExceptionHandler} to HTTP
 * {@code 428 Precondition Required} with errorCode {@code MFA_REQUIRED}, so
 * the Next.js BFF can surface an MFA step-up modal and resume the original
 * action with a fresh idempotency key.
 *
 * <p>Carries an opaque {@code challengeId} that the UI passes back on
 * {@code POST /api/v1/auth/mfa/verify} so the server can correlate the
 * challenge to the original action, preventing step-up replay.
 */
public class MfaRequiredException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final String challengeId;
    private final String channel;

    public MfaRequiredException(String challengeId, String channel, String message) {
        super("MFA_REQUIRED", message);
        this.challengeId = challengeId;
        this.channel = channel;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public String getChannel() {
        return channel;
    }
}
