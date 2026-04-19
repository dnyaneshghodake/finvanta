package com.finvanta.api.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Transfer request DTO
 */
public record TransferRequestDto(
        @NotBlank(message = "From account is required")
        String fromAccountId,

        @NotBlank(message = "Beneficiary account is required")
        String toAccountNumber,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        BigDecimal amount,

        @NotBlank(message = "Description is required")
        String description) {
}

