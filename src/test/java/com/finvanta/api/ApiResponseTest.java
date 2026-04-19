package com.finvanta.api;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBS ApiResponse Envelope Tests — standard response contract.
 *
 * Per RBI IT Governance Direction 2023 §8.5: all API responses must follow
 * a consistent envelope with status, data, errorCode, message, and timestamp.
 *
 * Tests the new errorWithData() factory added in this PR alongside the
 * existing success() and error() factories.
 */
class ApiResponseTest {

    @Nested
    @DisplayName("success() factories")
    class SuccessTests {

        @Test
        @DisplayName("success(data) sets SUCCESS status and data")
        void successWithData() {
            ApiResponse<String> response = ApiResponse.success("hello");

            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getData()).isEqualTo("hello");
            assertThat(response.getErrorCode()).isNull();
            assertThat(response.getMessage()).isNull();
            assertThat(response.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("success(data, message) sets SUCCESS status, data, and message")
        void successWithDataAndMessage() {
            ApiResponse<Integer> response = ApiResponse.success(42, "Account created");

            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getData()).isEqualTo(42);
            assertThat(response.getMessage()).isEqualTo("Account created");
            assertThat(response.getErrorCode()).isNull();
        }
    }

    @Nested
    @DisplayName("error() factory")
    class ErrorTests {

        @Test
        @DisplayName("error(code, message) sets ERROR status with no data")
        void errorWithCodeAndMessage() {
            ApiResponse<Void> response = ApiResponse.error("ACCOUNT_NOT_FOUND", "Not found");

            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(response.getMessage()).isEqualTo("Not found");
            assertThat(response.getData()).isNull();
            assertThat(response.getTimestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("errorWithData() factory — new in this PR")
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
        }

        @Test
        @DisplayName("errorWithData with null data behaves like error()")
        void errorWithNullData() {
            ApiResponse<String> response =
                    ApiResponse.errorWithData("SOME_ERROR", "msg", null);

            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getErrorCode()).isEqualTo("SOME_ERROR");
            assertThat(response.getData()).isNull();
        }
    }
}
