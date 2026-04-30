package com.finvanta.cbs.modules.customer.mapper;

import com.finvanta.cbs.modules.customer.dto.response.CustomerResponse;
import com.finvanta.domain.entity.Customer;
import com.finvanta.util.PiiMaskingUtil;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

/**
 * CBS Customer Module Mapper per Tier-1 DTO isolation standards.
 *
 * <p>Centralizes entity-to-DTO conversion with MANDATORY PII masking.
 * Per RBI IT Governance SS8.5: PAN masked to last 4 digits,
 * Aadhaar masked to last 4 digits. NO full PII in API responses.
 *
 * <p>This mapper is the SINGLE POINT where PII masking is enforced
 * for the Customer bounded context API responses.
 */
@Component
public class CustomerMapper {

    /**
     * Maps Customer entity to CustomerResponse DTO with PII masking.
     *
     * @param entity       the Customer JPA entity
     * @param businessDate current CBS business date for KYC expiry calculation
     * @return masked CustomerResponse DTO
     */
    public CustomerResponse toCustomerResponse(Customer entity, LocalDate businessDate) {
        if (entity == null) {
            return null;
        }

        String branchCode = null;
        String branchName = null;
        if (entity.getBranch() != null) {
            branchCode = entity.getBranch().getBranchCode();
            branchName = entity.getBranch().getBranchName();
        }

        return new CustomerResponse(
                entity.getId(),
                entity.getCustomerNumber(),
                entity.getFirstName(),
                entity.getMiddleName(),
                entity.getLastName(),
                buildFullName(entity),
                entity.getDateOfBirth(),
                PiiMaskingUtil.maskPan(entity.getPanNumber()),
                PiiMaskingUtil.maskAadhaar(entity.getAadhaarNumber()),
                entity.getMobileNumber(),
                entity.getEmail(),
                entity.getCustomerType(),
                entity.isKycVerified() ? "VERIFIED" : "PENDING",
                entity.isKycVerified(),
                entity.isKycExpired(businessDate),
                entity.getKycVerifiedDate(),
                entity.getKycRiskCategory(),
                entity.isActive(),
                branchCode,
                branchName,
                entity.getGender(),
                entity.getNationality(),
                entity.getMaritalStatus(),
                entity.getResidentStatus(),
                entity.getOccupationCode(),
                entity.isPep(),
                entity.getAddress(),
                entity.getCity(),
                entity.getState(),
                entity.getPinCode()
        );
    }

    private String buildFullName(Customer entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity.getFirstName());
        if (entity.getMiddleName() != null && !entity.getMiddleName().isBlank()) {
            sb.append(" ").append(entity.getMiddleName());
        }
        sb.append(" ").append(entity.getLastName());
        return sb.toString();
    }
}
