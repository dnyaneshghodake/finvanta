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
 * Verifies {@code @NotBlank} on
 * {@link com.finvanta.api.DepositAccountController.RejectRequest#reason()}
 * per the PR change at
 * {@code src/main/java/com/finvanta/api/DepositAccountController.java:77}.
 *
 * <p>The controller parameter is annotated {@code @Valid @RequestBody}, so
 * Bean Validation fires before the handler body. This test exercises the
 * record's constraint annotations directly via {@link Validator}.
 */
class RejectRequestValidationTest {

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
    @DisplayName("Blank, empty, or null reason violates @NotBlank")
    void blankReason_isRejected(String reason) {
        DepositAccountController.RejectRequest req =
                new DepositAccountController.RejectRequest(reason);

        Set<ConstraintViolation<DepositAccountController.RejectRequest>> violations =
                validator.validate(req);

        assertThat(violations).hasSize(1);
        ConstraintViolation<?> v = violations.iterator().next();
        assertThat(v.getPropertyPath().toString()).isEqualTo("reason");
        assertThat(v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                .isEqualTo("NotBlank");
    }

    @Test
    @DisplayName("Non-blank reason passes validation")
    void nonBlankReason_isAccepted() {
        DepositAccountController.RejectRequest req =
                new DepositAccountController.RejectRequest("Insufficient KYC documents");

        assertThat(validator.validate(req)).isEmpty();
    }
}
