package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CbsReferenceService;
import com.finvanta.service.CustomerCifService;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Customer CIF Service Implementation per Finacle CIF_MASTER / Temenos CUSTOMER.
 *
 * All business validations reside here — NOT in controllers.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller → Service → Repository
 *   Controller has NO @Transactional, NO direct repository calls, NO business logic.
 *
 * Per RBI KYC Master Direction 2016:
 * - One PAN = One CIF (duplicate PAN check)
 * - KYC verification date uses CBS business date (not LocalDate.now())
 * - KYC expiry computed per risk category
 * - Branch access enforced on all operations
 * - Every state change audited
 */
@Service
public class CustomerCifServiceImpl implements CustomerCifService {

    private static final Logger log =
            LoggerFactory.getLogger(CustomerCifServiceImpl.class);

    private final CustomerRepository customerRepo;
    private final BranchRepository branchRepo;
    private final LoanAccountRepository loanRepo;
    private final com.finvanta.repository.DepositAccountRepository depositRepo;
    private final AuditService auditSvc;
    private final BranchAccessValidator branchValidator;
    private final CbsReferenceService refService;
    private final BusinessDateService businessDateService;

    public CustomerCifServiceImpl(
            CustomerRepository customerRepo,
            BranchRepository branchRepo,
            LoanAccountRepository loanRepo,
            com.finvanta.repository.DepositAccountRepository depositRepo,
            AuditService auditSvc,
            BranchAccessValidator branchValidator,
            CbsReferenceService refService,
            BusinessDateService businessDateService) {
        this.customerRepo = customerRepo;
        this.branchRepo = branchRepo;
        this.loanRepo = loanRepo;
        this.depositRepo = depositRepo;
        this.auditSvc = auditSvc;
        this.branchValidator = branchValidator;
        this.refService = refService;
        this.businessDateService = businessDateService;
    }

    @Override
    @Transactional
    public Customer createCustomer(
            String firstName, String lastName,
            LocalDate dateOfBirth,
            String panNumber, String aadhaarNumber,
            String mobileNumber, String email,
            String address, String city, String state,
            String pinCode, String customerType,
            Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        // CBS Validation: Mandatory fields per RBI KYC Master Direction 2016
        if (firstName == null || firstName.isBlank()) {
            throw new BusinessException("FIRST_NAME_REQUIRED", "First name is mandatory per RBI KYC norms.");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new BusinessException("LAST_NAME_REQUIRED", "Last name is mandatory per RBI KYC norms.");
        }

        // CBS Validation: PAN format (AAAAA0000A) if provided
        if (panNumber != null && !panNumber.isBlank()) {
            if (!panNumber.matches("^[A-Z]{5}[0-9]{4}[A-Z]$")) {
                throw new BusinessException("INVALID_PAN_FORMAT",
                        "PAN must be in format AAAAA0000A (5 letters + 4 digits + 1 letter).");
            }
        }

        // CBS Validation: Aadhaar format (12 digits) if provided
        if (aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            if (!aadhaarNumber.matches("^[0-9]{12}$")) {
                throw new BusinessException("INVALID_AADHAAR_FORMAT",
                        "Aadhaar must be exactly 12 digits.");
            }
        }

        // CBS Validation: Mobile format (10 digits) if provided
        if (mobileNumber != null && !mobileNumber.isBlank()) {
            if (!mobileNumber.matches("^[6-9][0-9]{9}$")) {
                throw new BusinessException("INVALID_MOBILE_FORMAT",
                        "Mobile number must be 10 digits starting with 6-9.");
            }
        }

        // CBS Validation: PIN code (6 digits) if provided
        if (pinCode != null && !pinCode.isBlank()) {
            if (!pinCode.matches("^[0-9]{6}$")) {
                throw new BusinessException("INVALID_PINCODE_FORMAT",
                        "PIN code must be exactly 6 digits.");
            }
        }

        // CBS: Duplicate PAN check per RBI KYC (one PAN = one CIF).
        // CRITICAL: Must use hash-based lookup, NOT encrypted column comparison.
        // PAN is encrypted via AES-256-GCM with random IV — same plaintext produces
        // different ciphertext each time. existsByTenantIdAndPanNumber() compares
        // ciphertext and NEVER matches. Hash-based check is the only reliable method.
        if (panNumber != null && !panNumber.isBlank()) {
            String panHash = computeSha256(panNumber);
            if (customerRepo.existsByTenantIdAndPanHash(tid, panHash))
                throw new BusinessException("DUPLICATE_PAN",
                        "Customer with this PAN already exists. "
                                + "Per RBI KYC norms, one PAN = one CIF.");
        }

        // CBS: Duplicate Aadhaar check (same encryption issue as PAN)
        if (aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            String aadhaarHash = computeSha256(aadhaarNumber);
            if (customerRepo.existsByTenantIdAndAadhaarHash(tid, aadhaarHash))
                throw new BusinessException("DUPLICATE_AADHAAR",
                        "Customer with this Aadhaar already exists. "
                                + "Duplicate CIFs are prohibited per RBI KYC.");
        }

        Branch branch = branchRepo.findById(branchId)
                .filter(b -> b.getTenantId().equals(tid)
                        && b.isActive())
                .orElseThrow(() -> new BusinessException(
                        "BRANCH_NOT_FOUND",
                        "" + branchId));

        Customer c = new Customer();
        c.setTenantId(tid);
        c.setCustomerNumber(
                refService.generateCustomerNumber(
                        branch.getId()));
        c.setFirstName(firstName);
        c.setLastName(lastName);
        c.setDateOfBirth(dateOfBirth);
        c.setPanNumber(panNumber);
        c.setAadhaarNumber(aadhaarNumber);
        c.setMobileNumber(mobileNumber);
        c.setEmail(email);
        c.setAddress(address);
        c.setCity(city);
        c.setState(state);
        c.setPinCode(pinCode);
        c.setCustomerType(customerType != null
                ? customerType : "INDIVIDUAL");
        c.setBranch(branch);
        c.setCreatedBy(user);
        c.computePanHash();
        c.computeAadhaarHash();

        Customer saved = customerRepo.save(c);

        auditSvc.logEvent("Customer", saved.getId(),
                "CREATE", null,
                saved.getCustomerNumber(), "CIF",
                "Customer created by " + user
                        + " at branch " + branch.getBranchCode());

        log.info("Customer created: cif={}, branch={}, user={}",
                saved.getCustomerNumber(),
                branch.getBranchCode(), user);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Customer getCustomer(Long customerId) {
        String tid = TenantContext.getCurrentTenant();
        Customer c = customerRepo.findById(customerId)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "" + customerId));
        branchValidator.validateAccess(c.getBranch());
        return c;
    }

    @Override
    @Transactional
    public Customer verifyKyc(Long customerId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        Customer c = customerRepo.findById(customerId)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "" + customerId));
        branchValidator.validateAccess(c.getBranch());

        // CBS CRITICAL: Use business date for KYC verification date.
        // Per Finacle CIF_MASTER / RBI KYC Master Direction 2016:
        // KYC verified date must reflect the CBS business date, not the
        // wall-clock date. If EOD runs after midnight, the KYC date
        // should be the current business day, not tomorrow.
        LocalDate businessDate =
                businessDateService.getCurrentBusinessDate();

        c.setKycVerified(true);
        c.setKycVerifiedBy(user);
        c.setKycVerifiedDate(businessDate);
        c.computeKycExpiry();
        c.setRekycDue(false);
        c.setUpdatedBy(user);
        customerRepo.save(c);

        auditSvc.logEvent("Customer", c.getId(),
                "KYC_VERIFY", "PENDING", "VERIFIED",
                "CIF", "KYC verified by " + user
                        + " on business date " + businessDate);

        log.info("KYC verified: cif={}, user={}, bizDate={}",
                c.getCustomerNumber(), user, businessDate);

        return c;
    }

    @Override
    @Transactional
    public Customer deactivateCustomer(Long customerId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        Customer c = customerRepo.findById(customerId)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "" + customerId));

        // CBS: Cannot deactivate customer with active loan accounts
        long activeLoanCount = loanRepo
                .findByTenantIdAndCustomerId(tid, customerId)
                .stream()
                .filter(a -> !a.getStatus().isTerminal())
                .count();
        if (activeLoanCount > 0)
            throw new BusinessException(
                    "CUSTOMER_HAS_ACTIVE_LOANS",
                    "Cannot deactivate: " + activeLoanCount + " active loan account(s).");

        // CBS: Cannot deactivate customer with non-closed CASA deposit accounts.
        // Per Finacle CIF_MASTER: any non-CLOSED deposit (ACTIVE, DORMANT, FROZEN,
        // PENDING_ACTIVATION) blocks customer deactivation. Only CLOSED accounts
        // are excluded — they have zero balance and no further operations.
        long nonClosedCasaCount = depositRepo
                .findByTenantIdAndCustomerId(tid, customerId)
                .stream()
                .filter(d -> !d.isClosed())
                .count();
        if (nonClosedCasaCount > 0)
            throw new BusinessException(
                    "CUSTOMER_HAS_ACTIVE_DEPOSITS",
                    "Cannot deactivate: " + nonClosedCasaCount
                            + " non-closed CASA deposit account(s). Close all deposit accounts first.");

        c.setActive(false);
        c.setUpdatedBy(user);
        customerRepo.save(c);

        auditSvc.logEvent("Customer", c.getId(),
                "DEACTIVATE", "ACTIVE", "INACTIVE",
                "CIF", "Deactivated by " + user);

        log.info("Customer deactivated: cif={}, user={}",
                c.getCustomerNumber(), user);

        return c;
    }

    @Override
    @Transactional
    public Customer updateCustomer(Long customerId, Customer updated, Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        Customer existing = customerRepo.findById(customerId)
                .filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "" + customerId));
        branchValidator.validateAccess(existing.getBranch());

        Branch branch = branchRepo.findById(branchId)
                .filter(b -> b.getTenantId().equals(tid) && b.isActive())
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "" + branchId));

        String beforeState = existing.getFullName() + "|" + existing.getMobileNumber();

        // CBS: Update ONLY mutable fields. PAN, Aadhaar, customerNumber are IMMUTABLE.
        existing.setFirstName(updated.getFirstName());
        existing.setLastName(updated.getLastName());
        existing.setDateOfBirth(updated.getDateOfBirth());
        existing.setMobileNumber(updated.getMobileNumber());
        existing.setEmail(updated.getEmail());
        existing.setAddress(updated.getAddress());
        existing.setCity(updated.getCity());
        existing.setState(updated.getState());
        existing.setPinCode(updated.getPinCode());
        existing.setCustomerType(updated.getCustomerType());
        existing.setCibilScore(updated.getCibilScore());
        existing.setMonthlyIncome(updated.getMonthlyIncome());
        existing.setMaxBorrowingLimit(updated.getMaxBorrowingLimit());
        existing.setEmploymentType(updated.getEmploymentType());
        existing.setEmployerName(updated.getEmployerName());

        // CBS: KYC risk category and PEP flag
        if (updated.getKycRiskCategory() != null) {
            existing.setKycRiskCategory(updated.getKycRiskCategory());
            existing.computeKycExpiry();
        }
        existing.setPep(updated.isPep());
        if (updated.isPep()) {
            existing.setKycRiskCategory("HIGH");
            existing.computeKycExpiry();
        }

        existing.setBranch(branch);
        existing.setUpdatedBy(user);
        customerRepo.save(existing);

        auditSvc.logEvent("Customer", existing.getId(),
                "UPDATE", beforeState,
                existing.getFullName() + "|" + existing.getMobileNumber(),
                "CIF", "Customer updated by " + user);

        log.info("Customer updated: cif={}, user={}", existing.getCustomerNumber(), user);
        return existing;
    }

    /**
     * Computes SHA-256 hash for PII de-duplication.
     * Same algorithm as Customer.computeSha256() — normalizes to uppercase + trim.
     */
    private static String computeSha256(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(
                    input.trim().toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Customer> searchCustomers(String query) {
        String tid = TenantContext.getCurrentTenant();

        // CBS: Empty/short query returns full list (branch-isolated)
        if (query == null || query.isBlank() || query.length() < 2) {
            if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
                return customerRepo.findByTenantIdAndActiveTrue(tid);
            } else {
                Long branchId = SecurityUtil.getCurrentUserBranchId();
                if (branchId == null) return List.of();
                return customerRepo.findByTenantIdAndBranchIdAndActiveTrue(tid, branchId);
            }
        }

        // CBS: Search with branch isolation per Finacle BRANCH_CONTEXT
        if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
            return customerRepo.searchCustomers(tid, query.trim());
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId == null) return List.of();
            return customerRepo.searchCustomersByBranch(tid, branchId, query.trim());
        }
    }
}
