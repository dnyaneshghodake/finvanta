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
import com.finvanta.util.PiiHashUtil;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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

        // CBS Validation: centralized field validation (DRY)
        validateCustomerFields(tid, firstName, lastName, panNumber, aadhaarNumber,
                mobileNumber, pinCode, email);

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
    @Transactional
    public Customer createCustomerFromEntity(Customer c, Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        // CBS Validation: centralized field validation (DRY — same rules as createCustomer)
        validateCustomerFields(tid, c.getFirstName(), c.getLastName(), c.getPanNumber(),
                c.getAadhaarNumber(), c.getMobileNumber(), c.getPinCode(), c.getEmail());

        Branch branch = branchRepo.findById(branchId)
                .filter(b -> b.getTenantId().equals(tid) && b.isActive())
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "" + branchId));

        // CBS CRITICAL: Mass Assignment Protection per OWASP A4 / Finacle CIF_MASTER.
        // Spring MVC @ModelAttribute binds ALL request parameters to entity fields,
        // including BaseEntity fields (id, version) and security-sensitive fields
        // (kycVerified, kycVerifiedDate, active, ckycNumber, etc.).
        // A malicious POST with kycVerified=true&id=<existing_id> would:
        //   - Bypass the entire maker-checker KYC verification workflow (RBI violation)
        //   - Overwrite an existing customer record via JPA merge() (data corruption)
        // MUST reset all system-managed and security-sensitive fields before save.
        c.setId(null);
        c.setVersion(null);
        c.setKycVerified(false);
        c.setKycVerifiedDate(null);
        c.setKycVerifiedBy(null);
        c.setKycExpiryDate(null);
        c.setRekycDue(false);
        c.setActive(true);
        c.setCkycNumber(null);
        c.setCkycStatus("NOT_REGISTERED");
        c.setCkycUploadDate(null);
        c.setCkycDownloadDate(null);
        c.setCustomerGroupId(null);
        c.setCustomerGroupName(null);

        // CBS: Set system fields
        c.setTenantId(tid);
        c.setCustomerNumber(refService.generateCustomerNumber(branch.getId()));
        c.setBranch(branch);
        c.setCreatedBy(user);
        c.setUpdatedBy(null);
        c.setCustomerType(c.getCustomerType() != null ? c.getCustomerType() : "INDIVIDUAL");
        c.computePanHash();
        c.computeAadhaarHash();
        c.computeCkycAccountType();

        // CBS: PEP auto-sets HIGH risk per FATF
        if (c.isPep()) c.setKycRiskCategory("HIGH");

        Customer saved = customerRepo.save(c);

        auditSvc.logEvent("Customer", saved.getId(), "CREATE", null,
                saved.getCustomerNumber(), "CIF",
                "Customer created by " + user + " at branch " + branch.getBranchCode());

        log.info("Customer created: cif={}, branch={}, user={}",
                saved.getCustomerNumber(), branch.getBranchCode(), user);
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

        // CBS Tier-1 Maker-Checker: Verifier must NOT be the same user who created the CIF.
        // Per RBI internal controls / Finacle CIF_MASTER / Temenos CUSTOMER:
        // "The person who creates a record must not be the person who verifies it."
        // This prevents a single user from creating a fictitious CIF and self-approving KYC.
        if (user.equals(c.getCreatedBy())) {
            throw new BusinessException("SELF_VERIFY_PROHIBITED",
                    "KYC verification cannot be performed by the same user who created the customer ("
                            + user + "). Per RBI internal controls, maker and checker must be different users.");
        }

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
        // CBS Tier-1: Branch access enforcement on customer deactivation.
        // Per Finacle CIF_MASTER: even ADMIN-only operations must validate branch context
        // for defense-in-depth. Without this, a future role expansion could bypass isolation.
        branchValidator.validateAccess(c.getBranch());

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

        // CBS Validation: validate mutable fields on update (format checks only — no duplicate PAN/Aadhaar).
        // PAN/Aadhaar are IMMUTABLE after creation, so duplicate check is not needed on update.
        // But mobile, PIN, email formats must still be validated per RBI KYC norms.
        validateMutableFields(updated.getFirstName(), updated.getLastName(),
                updated.getMobileNumber(), updated.getPinCode(), updated.getEmail(),
                updated.getPermanentPinCode());

        // CBS: Detect identity-material field changes BEFORE applying updates.
        // Must compare old vs new values before overwriting existing fields.
        boolean identityChanged = !safeEquals(existing.getFirstName(), updated.getFirstName())
                || !safeEquals(existing.getLastName(), updated.getLastName())
                || !safeEquals(
                        existing.getDateOfBirth() != null ? existing.getDateOfBirth().toString() : null,
                        updated.getDateOfBirth() != null ? updated.getDateOfBirth().toString() : null)
                || !safeEquals(existing.getCustomerType(), updated.getCustomerType());

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

        // CBS CKYC: Demographics (mutable — can be updated on re-KYC)
        existing.setGender(updated.getGender());
        existing.setFatherName(updated.getFatherName());
        existing.setMotherName(updated.getMotherName());
        existing.setSpouseName(updated.getSpouseName());
        existing.setNationality(updated.getNationality());
        existing.setMaritalStatus(updated.getMaritalStatus());
        existing.setOccupationCode(updated.getOccupationCode());
        existing.setAnnualIncomeBand(updated.getAnnualIncomeBand());

        // CBS CKYC: KYC document details (mutable on re-KYC)
        existing.setKycMode(updated.getKycMode());
        existing.setPhotoIdType(updated.getPhotoIdType());
        existing.setPhotoIdNumber(updated.getPhotoIdNumber());
        existing.setAddressProofType(updated.getAddressProofType());
        existing.setAddressProofNumber(updated.getAddressProofNumber());

        // CBS CKYC: Permanent address (mutable)
        existing.setPermanentAddress(updated.getPermanentAddress());
        existing.setPermanentCity(updated.getPermanentCity());
        existing.setPermanentState(updated.getPermanentState());
        existing.setPermanentPinCode(updated.getPermanentPinCode());
        existing.setPermanentCountry(updated.getPermanentCountry());
        existing.setAddressSameAsPermanent(updated.isAddressSameAsPermanent());

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

        // CBS: Flag re-KYC if identity-material fields were changed.
        // Per Finacle CIF_MASTER / RBI KYC Direction: material changes to customer identity
        // (name, DOB, customer type) invalidate the existing KYC verification.
        // Only flag re-KYC if KYC was previously verified — new customers are already pending.
        if (identityChanged && existing.isKycVerified()) {
            existing.setRekycDue(true);
            log.info("Re-KYC flagged for cif={} due to identity-material field change by {}",
                    existing.getCustomerNumber(), user);
        }

        existing.setBranch(branch);
        existing.setUpdatedBy(user);
        customerRepo.save(existing);

        String reKycNote = identityChanged && existing.isKycVerified()
                ? " | RE-KYC FLAGGED (identity change)" : "";
        auditSvc.logEvent("Customer", existing.getId(),
                "UPDATE", beforeState,
                existing.getFullName() + "|" + existing.getMobileNumber(),
                "CIF", "Customer updated by " + user + reKycNote);

        log.info("Customer updated: cif={}, user={}", existing.getCustomerNumber(), user);
        return existing;
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    /**
     * CBS Centralized Customer Field Validation per RBI KYC Master Direction 2016.
     * Shared between createCustomer() and createCustomerFromEntity() to enforce DRY.
     * Validates: mandatory fields, PAN/Aadhaar/Mobile/PIN format, email format,
     * and duplicate PAN/Aadhaar via hash-based lookup.
     *
     * Per Finacle CIF_MASTER VALIDATE hook: all field-level validations run before
     * any persistence operation. Validation errors are fail-fast (first error thrown).
     */
    private void validateCustomerFields(String tid, String firstName, String lastName,
            String panNumber, String aadhaarNumber, String mobileNumber,
            String pinCode, String email) {
        // Mandatory fields
        if (firstName == null || firstName.isBlank())
            throw new BusinessException("FIRST_NAME_REQUIRED", "First name is mandatory per RBI KYC norms.");
        if (lastName == null || lastName.isBlank())
            throw new BusinessException("LAST_NAME_REQUIRED", "Last name is mandatory per RBI KYC norms.");

        // PAN format: AAAAA0000A (5 letters + 4 digits + 1 letter)
        if (panNumber != null && !panNumber.isBlank()) {
            if (!panNumber.matches("^[A-Z]{5}[0-9]{4}[A-Z]$"))
                throw new BusinessException("INVALID_PAN_FORMAT",
                        "PAN must be in format AAAAA0000A (5 letters + 4 digits + 1 letter).");
            // Duplicate PAN check via hash (encrypted column can't be compared)
            if (customerRepo.existsByTenantIdAndPanHash(tid, PiiHashUtil.computeSha256(panNumber)))
                throw new BusinessException("DUPLICATE_PAN",
                        "Customer with this PAN already exists. Per RBI KYC norms, one PAN = one CIF.");
        }

        // Aadhaar format: exactly 12 digits
        if (aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            if (!aadhaarNumber.matches("^[0-9]{12}$"))
                throw new BusinessException("INVALID_AADHAAR_FORMAT", "Aadhaar must be exactly 12 digits.");
            if (customerRepo.existsByTenantIdAndAadhaarHash(tid, PiiHashUtil.computeSha256(aadhaarNumber)))
                throw new BusinessException("DUPLICATE_AADHAAR",
                        "Customer with this Aadhaar already exists. Duplicate CIFs are prohibited per RBI KYC.");
        }

        // Mobile format: 10 digits starting with 6-9 (Indian mobile)
        if (mobileNumber != null && !mobileNumber.isBlank()) {
            if (!mobileNumber.matches("^[6-9][0-9]{9}$"))
                throw new BusinessException("INVALID_MOBILE_FORMAT",
                        "Mobile number must be 10 digits starting with 6-9.");
        }

        // PIN code format: exactly 6 digits
        if (pinCode != null && !pinCode.isBlank()) {
            if (!pinCode.matches("^[0-9]{6}$"))
                throw new BusinessException("INVALID_PINCODE_FORMAT", "PIN code must be exactly 6 digits.");
        }

        // Email format per RBI Digital Lending Guidelines 2022:
        // All customer communication channels must be validated.
        if (email != null && !email.isBlank()) {
            if (!email.matches("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"))
                throw new BusinessException("INVALID_EMAIL_FORMAT",
                        "Email address format is invalid.");
        }
    }

    /**
     * CBS Mutable Field Validation for customer update operations.
     * Validates format only — no duplicate PAN/Aadhaar checks (those are immutable).
     * Per Finacle CIF_MASTER: update operations must still validate data quality.
     */
    private void validateMutableFields(String firstName, String lastName,
            String mobileNumber, String pinCode, String email, String permanentPinCode) {
        if (firstName == null || firstName.isBlank())
            throw new BusinessException("FIRST_NAME_REQUIRED", "First name is mandatory per RBI KYC norms.");
        if (lastName == null || lastName.isBlank())
            throw new BusinessException("LAST_NAME_REQUIRED", "Last name is mandatory per RBI KYC norms.");
        if (mobileNumber != null && !mobileNumber.isBlank()
                && !mobileNumber.matches("^[6-9][0-9]{9}$"))
            throw new BusinessException("INVALID_MOBILE_FORMAT",
                    "Mobile number must be 10 digits starting with 6-9.");
        if (pinCode != null && !pinCode.isBlank()
                && !pinCode.matches("^[0-9]{6}$"))
            throw new BusinessException("INVALID_PINCODE_FORMAT", "PIN code must be exactly 6 digits.");
        if (permanentPinCode != null && !permanentPinCode.isBlank()
                && !permanentPinCode.matches("^[0-9]{6}$"))
            throw new BusinessException("INVALID_PINCODE_FORMAT", "Permanent PIN code must be exactly 6 digits.");
        if (email != null && !email.isBlank()
                && !email.matches("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"))
            throw new BusinessException("INVALID_EMAIL_FORMAT", "Email address format is invalid.");
    }

    /** Null-safe string equality check for identity-material change detection. */
    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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

        // CBS: PAN-based exact search via SHA-256 hash.
        // Per Finacle CIF_SEARCH: if query matches PAN format (AAAAA0000A), do hash lookup.
        // LIKE on encrypted pan_number column NEVER matches — hash is the only path.
        String trimmed = query.trim();
        if (trimmed.matches("^[A-Z]{5}[0-9]{4}[A-Z]$")) {
            String panHash = PiiHashUtil.computeSha256(trimmed);
            java.util.Optional<Customer> panMatch = customerRepo.findByTenantIdAndPanHashAndActiveTrue(tid, panHash);
            if (panMatch.isPresent()) {
                Customer c = panMatch.get();
                // CBS: Branch isolation — MAKER/CHECKER can only see their branch's customers
                if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
                    return List.of(c);
                }
                Long userBranch = SecurityUtil.getCurrentUserBranchId();
                if (userBranch != null && userBranch.equals(c.getBranch().getId())) {
                    return List.of(c);
                }
                return List.of(); // Customer exists but at different branch
            }
            // PAN not found — fall through to name/CIF/mobile search below
        }

        // CBS: Search with branch isolation per Finacle BRANCH_CONTEXT
        if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
            return customerRepo.searchCustomers(tid, trimmed);
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId == null) return List.of();
            return customerRepo.searchCustomersByBranch(tid, branchId, trimmed);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Customer> searchCustomers(String query, Pageable pageable) {
        String tid = TenantContext.getCurrentTenant();

        // CBS: Empty/short query returns paginated full list (branch-isolated)
        if (query == null || query.isBlank() || query.length() < 2) {
            if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
                return customerRepo.findByTenantIdAndActiveTrue(tid, pageable);
            } else {
                Long branchId = SecurityUtil.getCurrentUserBranchId();
                if (branchId == null) return Page.empty(pageable);
                return customerRepo.findByTenantIdAndBranchIdAndActiveTrue(tid, branchId, pageable);
            }
        }

        // CBS: PAN-based exact search via SHA-256 hash (returns single result as page)
        String trimmed = query.trim();
        if (trimmed.matches("^[A-Z]{5}[0-9]{4}[A-Z]$")) {
            String panHash = PiiHashUtil.computeSha256(trimmed);
            java.util.Optional<Customer> panMatch = customerRepo.findByTenantIdAndPanHashAndActiveTrue(tid, panHash);
            if (panMatch.isPresent()) {
                Customer c = panMatch.get();
                if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
                    return new PageImpl<>(List.of(c), pageable, 1);
                }
                Long userBranch = SecurityUtil.getCurrentUserBranchId();
                if (userBranch != null && userBranch.equals(c.getBranch().getId())) {
                    return new PageImpl<>(List.of(c), pageable, 1);
                }
                return Page.empty(pageable);
            }
        }

        // CBS: Paginated search with branch isolation
        if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
            return customerRepo.searchCustomersPaged(tid, trimmed, pageable);
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId == null) return Page.empty(pageable);
            return customerRepo.searchCustomersByBranchPaged(tid, branchId, trimmed, pageable);
        }
    }
}
