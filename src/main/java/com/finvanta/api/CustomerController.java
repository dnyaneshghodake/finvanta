package com.finvanta.api;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.CbsReferenceService;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.PiiMaskingUtil;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Customer CIF REST API per Finacle CIF_API / Temenos IRIS Customer.
 *
 * Thin orchestration layer — delegates to repositories and services.
 * Per RBI KYC Master Direction 2016 / Digital Lending Guidelines 2022.
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

    private final CustomerRepository customerRepo;
    private final BranchRepository branchRepo;
    private final LoanAccountRepository loanRepo;
    private final AuditService auditSvc;
    private final BranchAccessValidator branchValidator;
    private final CbsReferenceService refService;

    public CustomerController(
            CustomerRepository customerRepo,
            BranchRepository branchRepo,
            LoanAccountRepository loanRepo,
            AuditService auditSvc,
            BranchAccessValidator branchValidator,
            CbsReferenceService refService) {
        this.customerRepo = customerRepo;
        this.branchRepo = branchRepo;
        this.loanRepo = loanRepo;
        this.auditSvc = auditSvc;
        this.branchValidator = branchValidator;
        this.refService = refService;
    }

    // === CIF Lifecycle ===

    /** Create customer with auto-generated CIF number. MAKER/ADMIN. */
    @PostMapping
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<CustomerResponse>>
            createCustomer(
                    @Valid @RequestBody CreateCustomerRequest req) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        // CBS: Duplicate PAN check per RBI KYC (one PAN = one CIF)
        if (req.panNumber() != null
                && !req.panNumber().isBlank()) {
            if (customerRepo.existsByTenantIdAndPanNumber(
                    tid, req.panNumber()))
                throw new BusinessException("DUPLICATE_PAN",
                        "Customer with this PAN already exists");
        }

        Branch branch = branchRepo.findById(req.branchId())
                .filter(b -> b.getTenantId().equals(tid)
                        && b.isActive())
                .orElseThrow(() -> new BusinessException(
                        "BRANCH_NOT_FOUND",
                        "" + req.branchId()));

        Customer c = new Customer();
        c.setTenantId(tid);
        c.setCustomerNumber(
                refService.generateCustomerNumber(
                        branch.getId()));
        c.setFirstName(req.firstName());
        c.setLastName(req.lastName());
        c.setDateOfBirth(req.dateOfBirth());
        c.setPanNumber(req.panNumber());
        c.setAadhaarNumber(req.aadhaarNumber());
        c.setMobileNumber(req.mobileNumber());
        c.setEmail(req.email());
        c.setAddress(req.address());
        c.setCity(req.city());
        c.setState(req.state());
        c.setPinCode(req.pinCode());
        c.setCustomerType(req.customerType() != null
                ? req.customerType() : "INDIVIDUAL");
        c.setBranch(branch);
        c.setCreatedBy(user);
        c.computePanHash();
        c.computeAadhaarHash();

        Customer saved = customerRepo.save(c);

        auditSvc.logEvent("Customer", saved.getId(),
                "CREATE", null,
                saved.getCustomerNumber(), "CIF",
                "API: Customer created by " + user);

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
        String tid = TenantContext.getCurrentTenant();
        Customer c = customerRepo.findById(id)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "" + id));
        branchValidator.validateAccess(c.getBranch());
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c)));
    }

    /** Verify KYC. CHECKER/ADMIN. */
    @PostMapping("/{id}/verify-kyc")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<CustomerResponse>>
            verifyKyc(@PathVariable Long id) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        Customer c = customerRepo.findById(id)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "" + id));
        branchValidator.validateAccess(c.getBranch());

        c.setKycVerified(true);
        c.setKycVerifiedBy(user);
        c.setKycVerifiedDate(java.time.LocalDate.now());
        c.computeKycExpiry();
        c.setRekycDue(false);
        c.setUpdatedBy(user);
        customerRepo.save(c);

        auditSvc.logEvent("Customer", c.getId(),
                "KYC_VERIFY", "PENDING", "VERIFIED",
                "CIF", "API: KYC verified by " + user);

        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c),
                "KYC verified"));
    }

    /** Deactivate customer. ADMIN only. */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<CustomerResponse>>
            deactivate(@PathVariable Long id) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        Customer c = customerRepo.findById(id)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "" + id));

        long active = loanRepo
                .findByTenantIdAndCustomerId(tid, id)
                .stream()
                .filter(a -> !a.getStatus().isTerminal())
                .count();
        if (active > 0)
            throw new BusinessException(
                    "CUSTOMER_HAS_ACTIVE_ACCOUNTS",
                    active + " active loan account(s)");

        c.setActive(false);
        c.setUpdatedBy(user);
        customerRepo.save(c);

        auditSvc.logEvent("Customer", c.getId(),
                "DEACTIVATE", "ACTIVE", "INACTIVE",
                "CIF", "API: Deactivated by " + user);

        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c),
                "Customer deactivated"));
    }

    /** Search customers. Branch-scoped for MAKER/CHECKER. */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>>
            search(@RequestParam String q) {
        String tid = TenantContext.getCurrentTenant();
        if (q == null || q.length() < 2)
            throw new BusinessException("INVALID_SEARCH",
                    "Search query must be at least 2 chars");

        List<Customer> results;
        if (SecurityUtil.isAdminRole()) {
            results = customerRepo.searchCustomers(
                    tid, q.trim());
        } else {
            Long branchId =
                    SecurityUtil.getCurrentUserBranchId();
            if (branchId == null)
                return ResponseEntity.ok(
                        ApiResponse.success(List.of()));
            results = customerRepo
                    .searchCustomersByBranch(
                            tid, branchId, q.trim());
        }

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
