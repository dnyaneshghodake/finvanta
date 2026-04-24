package com.finvanta.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies Bean Validation constraints on
 * {@link com.finvanta.api.AuditApiController.ScreenAccessRequest}.
 *
 * <p>The controller parameter is annotated {@code @Valid @RequestBody}, so
 * these constraints fire before the handler body executes. Exercises the
 * constraints directly via {@link Validator} to pin behavior without spinning
 * up the full Spring MVC stack.
 */
class ScreenAccessRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("Blank, empty, or null screen violates @NotBlank")
    void blankScreen_isRejected(String screen) {
        AuditApiController.ScreenAccessRequest req =
                new AuditApiController.ScreenAccessRequest(screen, null, null);

        Set<ConstraintViolation<AuditApiController.ScreenAccessRequest>> violations =
                validator.validate(req);

        assertThat(violations).hasSize(1);
        ConstraintViolation<?> v = violations.iterator().next();
        assertThat(v.getPropertyPath().toString()).isEqualTo("screen");
        assertThat(v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                .isEqualTo("NotBlank");
    }

    @Test
    @DisplayName("Typical payload passes validation")
    void validPayload_isAccepted() {
        AuditApiController.ScreenAccessRequest req =
                new AuditApiController.ScreenAccessRequest(
                        "/customers/3", "/customers", "/dashboard");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("Null optional fields pass validation")
    void nullOptionalFields_areAccepted() {
        AuditApiController.ScreenAccessRequest req =
                new AuditApiController.ScreenAccessRequest("/dashboard", null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("Oversized screen is rejected by @Size(max=200)")
    void oversizedScreen_isRejected() {
        String tooLong = "/" + "a".repeat(200);
        AuditApiController.ScreenAccessRequest req =
                new AuditApiController.ScreenAccessRequest(tooLong, null, null);

        Set<ConstraintViolation<AuditApiController.ScreenAccessRequest>> violations =
                validator.validate(req);

        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("screen")
                        && v.getConstraintDescriptor().getAnnotation()
                                .annotationType().getSimpleName().equals("Size"));
    }

    @Test
    @DisplayName("Oversized returnTo is rejected by @Size(max=500)")
    void oversizedReturnTo_isRejected() {
        String tooLong = "/" + "a".repeat(500);
        AuditApiController.ScreenAccessRequest req =
                new AuditApiController.ScreenAccessRequest(
                        "/dashboard", tooLong, null);

        Set<ConstraintViolation<AuditApiController.ScreenAccessRequest>> violations =
                validator.validate(req);

        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("returnTo"));
    }
}
