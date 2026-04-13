package com.finvanta.api;

import com.finvanta.domain.entity.Customer;
import com.finvanta.service.CustomerCifService;
import com.finvanta.util.PiiMaskingUtil;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Customer CIF REST API per Finacle CIF_API / Temenos IRIS Customer.
 *
 * Thin orchestration layer — delegates ALL business logic to CustomerCifService.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller: @PreAuthorize, request/response mapping, NO @Transactional
 *   Service: @Transactional, business validations, repository calls, audit
 *
 * CBS Role Matrix:
 *   MAKER   → create customer
 *   CHECKER → verify KYC
 *   ADMIN   → deactivate, all MAKER+CHECKER ops
 *
 * PII Security per RBI IT Governance §8.5:
 * - PAN/Aadhaar NEVER exposed in API responses (masked to last 4)
 * - Full PII only in encrypted DB columns (AES-256-GCM)
 */
@RestController("customerCifApiController")
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerCifService customerService;

    public CustomerController(
            CustomerCifService customerService) {
        this.customerService = customerService;
    }

    // === CIF Lifecycle ===

    /** Create customer with auto-generated CIF number. MAKER/ADMIN. */
    @PostMapping
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            createCustomer(
                    @Valid @RequestBody CreateCustomerRequest req) {
        Customer saved = customerService.createCustomer(
                req.firstName(), req.lastName(),
                req.dateOfBirth(), req.panNumber(),
                req.aadhaarNumber(), req.mobileNumber(),
                req.email(), req.address(), req.city(),
                req.state(), req.pinCode(),
                req.customerType(), req.branchId());

        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(saved),
                "Customer created: "
                        + saved.getCustomerNumber()));
    }

    /** Get customer by ID. Branch access enforced. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            getCustomer(@PathVariable Long id) {
        Customer c = customerService.getCustomer(id);
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c)));
    }

    /** Verify KYC. CHECKER/ADMIN. Uses CBS business date. */
    @PostMapping("/{id}/verify-kyc")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            verifyKyc(@PathVariable Long id) {
        Customer c = customerService.verifyKyc(id);
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c),
                "KYC verified"));
    }

    /** Deactivate customer. ADMIN only. */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            deactivate(@PathVariable Long id) {
        Customer c = customerService
                .deactivateCustomer(id);
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c),
                "Customer deactivated"));
    }

    /** Search customers. Branch-scoped for MAKER/CHECKER. */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>>
            search(@RequestParam String q) {
        var results = customerService
                .searchCustomers(q);
        var items = results.stream()
                .map(CustomerResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Request DTOs ===

    public record CreateCustomerRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            java.time.LocalDate dateOfBirth,
            String panNumber,
            String aadhaarNumber,
            String mobileNumber,
            String email,
            String address,
            String city,
            String state,
            String pinCode,
            String customerType,
            @NotNull Long branchId) {}

    // === Response DTOs (PII masked per RBI) ===

    public record CustomerResponse(
            Long id,
            String customerNumber,
            String firstName,
            String lastName,
            String maskedPan,
            String maskedAadhaar,
            String maskedMobile,
            String email,
            String customerType,
            boolean kycVerified,
            String kycRiskCategory,
            String kycExpiryDate,
            boolean active,
            String branchCode,
            String createdAt) {
        static CustomerResponse from(Customer c) {
            return new CustomerResponse(
                    c.getId(),
                    c.getCustomerNumber(),
                    c.getFirstName(),
                    c.getLastName(),
                    PiiMaskingUtil.maskPan(
                            c.getPanNumber()),
                    PiiMaskingUtil.maskAadhaar(
                            c.getAadhaarNumber()),
                    PiiMaskingUtil.maskMobile(
                            c.getMobileNumber()),
                    c.getEmail(),
                    c.getCustomerType(),
                    c.isKycVerified(),
                    c.getKycRiskCategory(),
                    c.getKycExpiryDate() != null
                            ? c.getKycExpiryDate()
                            .toString() : null,
                    c.isActive(),
                    c.getBranch() != null
                            ? c.getBranch()
                            .getBranchCode() : null,
                    c.getCreatedAt() != null
                            ? c.getCreatedAt()
                            .toString() : null);
        }
    }
}
