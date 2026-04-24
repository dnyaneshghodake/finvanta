package com.finvanta.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the 401 entry point now produces well-formed, Jackson-serialized
 * JSON per the refactor at
 * {@code src/main/java/com/finvanta/config/SecurityConfig.java:126-143}.
 *
 * <p>Prior behavior concatenated strings, risking response-splitting /
 * JSON-injection if any interpolated field (e.g. {@code correlationId}) were
 * to contain control characters. The refactor builds the body as a
 * {@code LinkedHashMap} and serializes via a static {@link ObjectMapper}.
 *
 * <p>Timestamps switched from {@code LocalDateTime} to
 * {@code OffsetDateTime.now(UTC)}, so the {@code meta.timestamp} field must
 * parse as a UTC-offset ISO-8601 instant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityConfigUnauthorizedResponseTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("401 response is valid JSON with expected shape and UTC timestamp")
    void unauthorizedRequest_returnsJacksonJson() throws Exception {
        // Any non-permitAll path triggers the entry point. /api/v1/auth/** is
        // permitAll, /actuator/health is permitAll — pick a clearly secured
        // path that requires authentication.
        ResponseEntity<String> resp = rest.getForEntity(
                "/api/v1/customers", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());

        String body = resp.getBody();
        assertThat(body).isNotBlank();

        // Must parse as JSON — the single strongest signal that Jackson (not
        // string concat) produced the body.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(body);

        assertThat(root.path("status").asText()).isEqualTo("ERROR");
        assertThat(root.path("errorCode").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(root.path("message").asText())
                .isEqualTo("Authentication required. Provide Bearer token.");

        JsonNode error = root.path("error");
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(error.path("severity").asText()).isEqualTo("HIGH");
        assertThat(error.path("action").asText())
                .isEqualTo("Login to obtain an access token");

        JsonNode meta = root.path("meta");
        assertThat(meta.path("apiVersion").asText()).isEqualTo("v1");
        assertThat(meta.path("correlationId").isTextual()).isTrue();

        // UTC ISO-8601 timestamp (ends in 'Z' or has a +00:00 offset).
        String ts = meta.path("timestamp").asText();
        assertThat(ts).isNotBlank();
        assertThatCode(() -> OffsetDateTime.parse(ts))
                .as("meta.timestamp must be an ISO-8601 OffsetDateTime, was: %s", ts)
                .doesNotThrowAnyException();
    }
}
