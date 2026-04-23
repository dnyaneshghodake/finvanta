package com.finvanta.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS CASA Account Opening Request per Finacle ACCTOPN / Temenos ACCOUNT.OPENING.
 *
 * <p>Flat DTO — all 29 API fields from the frontend Account Opening Blueprint.
 * Unknown fields are ignored for forward compatibility ({@code @JsonIgnoreProperties}).
 * BFF injects branchId from server session; frontend sends it but BFF overrides.
 *
 * <p>Per RBI KYC Master Direction 2016: PAN/Aadhaar are encrypted at entity level
 * via {@code PiiEncryptionConverter}. This DTO carries plaintext for validation only.
 *
 * <p>Extracted to a standalone class to avoid circular type reference between
 * {@code DepositAccountController} and {@code DepositAccountService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAccountRequest(
        // §1 Product Selection
        @NotNull Long customerId,
        @NotNull Long branchId,
        @NotBlank String accountType,
        String productCode,
        String currencyCode,
        @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal initialDeposit,
        // §3 KYC & Regulatory
        @Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]", message = "PAN must be 10-char alphanumeric (e.g. ABCDE1234F)")
        String panNumber,
        @Pattern(regexp = "\\d{12}", message = "Aadhaar must be exactly 12 digits")
        String aadhaarNumber,
        String kycStatus,
        Boolean pepFlag,
        // §4 Personal Details
        // CBS: fullName is optional — if absent, the service layer uses Customer.getFullName().
        // The JSP path passes null (backward compat); the REST BFF may or may not send it.
        // Per Finacle ACCTOPN: fullName on the account is a denormalized snapshot, not mandatory.
        @Size(max = 200) String fullName,
        LocalDate dateOfBirth,
        String gender,
        @Size(max = 200) String fatherSpouseName,
        String nationality,
        // §5 Contact Details
        @Pattern(regexp = "[6-9]\\d{9}", message = "Mobile must be 10-digit Indian number starting with 6-9")
        String mobileNumber,
        @Email String email,
        // §6 Address
        @Size(max = 500) String addressLine1,
        @Size(max = 500) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        @Pattern(regexp = "\\d{6}", message = "PIN code must be exactly 6 digits")
        String pinCode,
        // §7 Occupation & Financial Profile
        String occupation,
        String annualIncome,
        String sourceOfFunds,
        // §8 Nominee
        @Size(max = 200) String nomineeName,
        String nomineeRelationship,
        // §9 FATCA / CRS
        Boolean usTaxResident,
        // §10 Account Configuration
        Boolean chequeBookRequired,
        Boolean debitCardRequired,
        Boolean smsAlerts) {}
