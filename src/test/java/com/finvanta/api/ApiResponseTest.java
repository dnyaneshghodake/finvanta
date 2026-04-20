package com.finvanta.api;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBS Tier-1 ApiResponse Envelope Tests — standard response contract.
 *
 * Per RBI IT Governance Direction 2023 §8.5: all API responses must follow
 * a consistent envelope with status, data, errorCode, message, timestamp,
 * meta (apiVersion, correlationId), and structured error detail.
 *
 * Tests all factory methods and verifies the Tier-1 CBS envelope contract:
 *   - meta.apiVersion is always "v1"
 *   - meta.timestamp is always present
 *   - error detail mirrors the flat errorCode/message for backward compatibility
 *   - severity and action are carried in the structured error object
 */
class ApiResponseTest {

    @Nested
    @DisplayName("success() factories")
    class SuccessTests {

        @Test
        @DisplayName("success(data) sets SUCCESS status and data with meta")
        void successWithData() {
            ApiResponse<String> response = ApiResponse.success("hello");

            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getData()).isEqualTo("hello");
            assertThat(response.getErrorCode()).isNull();
            assertThat(response.getMessage()).isNull();
            assertThat(response.getTimestamp()).isNotNull();
            // Tier-1: meta is always present
            assertThat(response.getMeta()).isNotNull();
            assertThat(response.getMeta().apiVersion()).isEqualTo("v1");
            assertThat(response.getMeta().timestamp()).isNotNull();
            // Tier-1: no error detail on success
            assertThat(response.getError()).isNull();
        }

        @Test
        @DisplayName("success(data, message) sets SUCCESS status, data, and message")
        void successWithDataAndMessage() {
            ApiResponse<Integer> response = ApiResponse.success(42, "Account created");

            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getData()).isEqualTo(42);
            assertThat(response.getMessage()).isEqualTo("Account created");
            assertThat(response.getErrorCode()).isNull();
            assertThat(response.getMeta().apiVersion()).isEqualTo("v1");
        }
    }

    @Nested
    @DisplayName("error() factory")
    class ErrorTests {

        @Test
        @DisplayName("error(code, message) sets ERROR with structured error detail")
        void errorWithCodeAndMessage() {
            ApiResponse<Void> response = ApiResponse.error("ACCOUNT_NOT_FOUND", "Not found");

            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(response.getMessage()).isEqualTo("Not found");
            assertThat(response.getData()).isNull();
            assertThat(response.getTimestamp()).isNotNull();
            // Tier-1: structured error mirrors flat fields
            assertThat(response.getError()).isNotNull();
            assertThat(response.getError().code()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(response.getError().message()).isEqualTo("Not found");
            assertThat(response.getError().severity()).isNull();
            assertThat(response.getError().action()).isNull();
            // Tier-1: meta always present
            assertThat(response.getMeta()).isNotNull();
            assertThat(response.getMeta().apiVersion()).isEqualTo("v1");
        }

        @Test
        @DisplayName("error(code, message, severity, action) carries full Tier-1 error detail")
        void errorWithSeverityAndAction() {
            ApiResponse<Void> response = ApiResponse.error(
                    "CBS-ACCT-007", "Insufficient account balance",
                    "HIGH", "Verify available balance before initiating transfer");

            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getErrorCode()).isEqualTo("CBS-ACCT-007");
            assertThat(response.getError()).isNotNull();
            assertThat(response.getError().code()).isEqualTo("CBS-ACCT-007");
            assertThat(response.getError().severity()).isEqualTo("HIGH");
            assertThat(response.getError().action()).isEqualTo(
                    "Verify available balance before initiating transfer");
        }
    }

    @Nested
    @DisplayName("errorWithData() factory")
    class ErrorWithDataTests {

        @Test
        @DisplayName("Carries error fields AND a data payload for MFA step-up")
        void errorWithDataCarriesPayload() {
            Map<String, String> payload = Map.of(
                    "challengeId", "abc-123",
                    "channel", "TOTP");

            ApiResponse<Map<String, String>> response =
                    ApiResponse.errorWithData("MFA_REQUIRED", "Step-up needed", payload);

            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getErrorCode()).isEqualTo("MFA_REQUIRED");
            assertThat(response.getMessage()).isEqualTo("Step-up needed");
            assertThat(response.getData()).containsEntry("challengeId", "abc-123");
            assertThat(response.getData()).containsEntry("channel", "TOTP");
            assertThat(response.getTimestamp()).isNotNull();
            // Tier-1: structured error present alongside data
            assertThat(response.getError()).isNotNull();
            assertThat(response.getError().code()).isEqualTo("MFA_REQUIRED");
        }

        @Test
        @DisplayName("errorWithData with null data behaves like error()")
        void errorWithNullData() {
            ApiResponse<String> response =
                    ApiResponse.errorWithData("SOME_ERROR", "msg", null);

            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getErrorCode()).isEqualTo("SOME_ERROR");
            assertThat(response.getData()).isNull();
            assertThat(response.getError()).isNotNull();
            assertThat(response.getError().code()).isEqualTo("SOME_ERROR");
        }
    }
}
