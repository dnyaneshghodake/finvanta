package com.finvanta.cbs.modules.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * CBS CASA Account Opening Request DTO per CBS ACCTOPN standard.
 *
 * <p>Decoupled from the {@code DepositAccount} entity to enforce API contract
 * stability per Tier-1 CBS standards. Schema changes in the persistence model
 * do NOT propagate to the API contract.
 *
 * <p>Validated at the controller boundary via Jakarta Bean Validation.
 * Complex business validations (KYC status, product eligibility, branch access)
 * are enforced in {@code AccountValidator}.
 */
public record OpenAccountRequest(

    @NotNull(message = "Customer ID is required")
    Long customerId,

    @NotBlank(message = "Account type is required")
    String accountType,

    @NotBlank(message = "Product code is required")
    String productCode,

    @NotNull(message = "Branch ID is required")
    Long branchId,

    @Positive(message = "Initial deposit must be positive")
    BigDecimal initialDeposit,

    String currencyCode,

    String nomineeFirstName,

    String nomineeLastName,

    String nomineeRelationship,

    String jointHolderCif,

    String operatingInstructions,

    String idempotencyKey
) {
    public OpenAccountRequest {
        if (currencyCode == null || currencyCode.isBlank()) {
            currencyCode = "INR";
        }
    }
}
