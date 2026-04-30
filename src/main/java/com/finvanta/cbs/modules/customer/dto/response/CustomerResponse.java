package com.finvanta.cbs.modules.customer.dto.response;

import java.time.LocalDate;

/**
 * CBS CIF Response DTO per CBS CIF_INQ standard.
 *
 * <p>PII fields (PAN, Aadhaar) are ALWAYS masked in this DTO.
 * The masking is enforced by {@code CustomerMapper} -- the entity itself
 * holds the full (encrypted) values but they never reach the API layer.
 *
 * <p>Per RBI IT Governance SS8.5: no full PAN or Aadhaar in API responses.
 */
public record CustomerResponse(
    Long id,
    String customerNumber,
    String firstName,
    String middleName,
    String lastName,
    String fullName,
    LocalDate dateOfBirth,
    String maskedPan,
    String maskedAadhaar,
    String mobileNumber,
    String email,
    String customerType,
    String kycStatus,
    boolean kycVerified,
    boolean kycExpired,
    LocalDate kycVerifiedDate,
    String kycRiskCategory,
    boolean active,
    String branchCode,
    String branchName,
    String gender,
    String nationality,
    String maritalStatus,
    String residentStatus,
    String occupationCode,
    Boolean pep,
    String address,
    String city,
    String state,
    String pinCode
) {
}
