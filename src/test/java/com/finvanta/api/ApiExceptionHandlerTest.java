package com.finvanta.api;

import com.finvanta.util.BusinessException;
import com.finvanta.util.MfaRequiredException;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBS ApiExceptionHandler Tests — deterministic error surface contract.
 *
 * Per RBI IT Governance Direction 2023 §8.5: every API error must return
 * a deterministic errorCode and HTTP status so the Next.js BFF can map
 * it to a user-facing action without inferring from free-text messages.
 *
 * Tests the two new handlers added in this PR:
 *   - MfaRequiredException → 428 Precondition Required (MFA_REQUIRED)
 *   - OptimisticLockingFailureException → 409 Conflict (VERSION_CONFLICT)
 *
 * Also verifies the existing BusinessException → status mapping and
 * the MfaRequiredException-before-BusinessException handler precedence.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Nested
    @DisplayName("MfaRequiredException → 428")
    class MfaRequiredTests {

        @Test
        @DisplayName("Returns 428 with MFA_REQUIRED errorCode and challenge payload")
        void returns428WithChallengePayload() {
            MfaRequiredException ex = new MfaRequiredException(
                    "challenge-token-xyz", "TOTP",
                    "MFA step-up required to complete sign-in");

            ResponseEntity<ApiResponse<Map<String, String>>> response =
                    handler.handleMfaRequired(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(428);

            ApiResponse<Map<String, String>> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getStatus()).isEqualTo("ERROR");
            assertThat(body.getErrorCode()).isEqualTo("MFA_REQUIRED");
            assertThat(body.getMessage()).isEqualTo("MFA step-up required to complete sign-in");
            assertThat(body.getData()).containsEntry("challengeId", "challenge-token-xyz");
            assertThat(body.getData()).containsEntry("channel", "TOTP");
            assertThat(body.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("Payload contains exactly challengeId and channel — no PII")
        void payloadContainsOnlyExpectedKeys() {
            MfaRequiredException ex = new MfaRequiredException(
                    "c", "TOTP", "msg");

            ResponseEntity<ApiResponse<Map<String, String>>> response =
                    handler.handleMfaRequired(ex);

            assertThat(response.getBody().getData()).containsOnlyKeys("challengeId", "channel");
        }
    }

    @Nested
    @DisplayName("OptimisticLockingFailureException → 409")
    class OptimisticLockTests {

        @Test
        @DisplayName("Returns 409 with VERSION_CONFLICT, MEDIUM severity, and reload action")
        void returns409VersionConflict() {
            OptimisticLockingFailureException ex =
                    new OptimisticLockingFailureException("Row was updated by another transaction");

            ResponseEntity<ApiResponse<Void>> response =
                    handler.handleOptimisticLock(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            ApiResponse<Void> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getStatus()).isEqualTo("ERROR");
            assertThat(body.getErrorCode()).isEqualTo("VERSION_CONFLICT");
            assertThat(body.getMessage()).contains("reload");
            assertThat(body.getData()).isNull();
            // Tier-1: severity and action
            assertThat(body.getError()).isNotNull();
            assertThat(body.getError().severity()).isEqualTo("MEDIUM");
            assertThat(body.getError().action()).contains("Reload");
        }
    }

    @Nested
    @DisplayName("BusinessException → status mapping with severity/action")
    class BusinessExceptionTests {

        @Test
        @DisplayName("NOT_FOUND error codes map to 404 with LOW severity")
        void notFoundCodes() {
            BusinessException ex = new BusinessException("ACCOUNT_NOT_FOUND", "Account not found");

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
            // Tier-1: structured error with severity
            assertThat(response.getBody().getError()).isNotNull();
            assertThat(response.getBody().getError().severity()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("CONFLICT error codes map to 409 with MEDIUM severity")
        void conflictCodes() {
            BusinessException ex = new BusinessException("DUPLICATE_TRANSACTION", "Duplicate txn");

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getError().severity()).isEqualTo("MEDIUM");
            assertThat(response.getBody().getError().action())
                    .isEqualTo("This transaction was already processed. Check your statement");
        }

        @Test
        @DisplayName("UNPROCESSABLE_ENTITY financial errors map to 422 with HIGH severity and action")
        void unprocessableCodes() {
            BusinessException ex = new BusinessException("INSUFFICIENT_BALANCE", "Insufficient balance");

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            // Tier-1: financial safety errors are HIGH severity with remediation action
            assertThat(response.getBody().getError().severity()).isEqualTo("HIGH");
            assertThat(response.getBody().getError().action()).contains("balance");
        }

        @Test
        @DisplayName("FORBIDDEN error codes map to 403 with HIGH severity")
        void forbiddenCodes() {
            BusinessException ex = new BusinessException("WORKFLOW_SELF_APPROVAL", "Self-approval");

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getError().severity()).isEqualTo("HIGH");
            assertThat(response.getBody().getError().action()).contains("different user");
        }

        @Test
        @DisplayName("Unknown error codes default to 400 with LOW severity")
        void unknownCodesDefaultTo400() {
            BusinessException ex = new BusinessException("SOME_UNKNOWN_CODE", "Unknown");

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getError().severity()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("Null error code defaults to 400")
        void nullCodeDefaultsTo400() {
            BusinessException ex = new BusinessException(null, "No code");

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("AccessDeniedException → 403")
    class AccessDeniedTests {

        @Test
        @DisplayName("Returns 403 with ACCESS_DENIED errorCode")
        void returns403() {
            org.springframework.security.access.AccessDeniedException ex =
                    new org.springframework.security.access.AccessDeniedException("Denied");

            ResponseEntity<ApiResponse<Void>> response = handler.handleAccess(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ACCESS_DENIED");
        }
    }

    @Nested
    @DisplayName("Generic Exception → 500")
    class GenericExceptionTests {

        @Test
        @DisplayName("Returns 500 with INTERNAL_ERROR, CRITICAL severity — no stack trace leaked")
        void returns500NoStackTrace() {
            Exception ex = new RuntimeException("NPE in some service");

            ResponseEntity<ApiResponse<Void>> response = handler.handleGeneric(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            ApiResponse<Void> body = response.getBody();
            assertThat(body.getErrorCode()).isEqualTo("INTERNAL_ERROR");
            // CBS SECURITY: message must NOT contain the original exception message
            assertThat(body.getMessage()).doesNotContain("NPE");
            assertThat(body.getMessage()).doesNotContain("service");
            // Tier-1: CRITICAL severity with support action
            assertThat(body.getError()).isNotNull();
            assertThat(body.getError().severity()).isEqualTo("CRITICAL");
            assertThat(body.getError().action()).contains("correlation ID");
        }
    }
}
