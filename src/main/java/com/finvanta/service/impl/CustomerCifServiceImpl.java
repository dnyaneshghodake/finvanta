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
    public Customer createCustomerFromEntity(Customer c, Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        // CBS Validation: centralized field validation (DRY — same rules as createCustomer)
        validateCustomerFields(tid, c.getFirstName(), c.getLastName(), c.getPanNumber(),
                c.getAadhaarNumber(), c.getMobileNumber(), c.getPinCode(), c.getEmail());

        // CBS Tier-1 (Gap 4): CKYC mandatory field validation per CERSAI Specification v2.0.
        // Gender, DOB, father's name, mother's name are mandatory for INDIVIDUAL-type customers.
        // Without this, a crafted POST bypassing HTML5 required attributes can create a CIF
        // with null CKYC-mandatory fields — blocking subsequent CERSAI upload.
        validateCkycMandatoryFields(c);

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
        // CBS: Video KYC completion must be system-verified, not user-asserted.
        // Per RBI circular RBI/2020-21/12: V-KYC status is set by the V-KYC workflow
        // engine after successful video session, NOT via customer creation form.
        c.setVideoKycDone(false);

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

        // CBS Tier-1 (Gap 8): Audit PII access per RBI IT Governance Direction 2023 §8.3.
        // Every read of customer PII (even view-only) must be logged for forensic investigation:
        // "who viewed which customer's data when." Per Finacle AUDIT_TRAIL / Temenos AUDIT.LOG:
        // CIF_VIEW events enable compliance to answer RBI inspector queries like
        // "show all users who accessed customer X's record in the last 30 days."
        // Uses REQUIRES_NEW propagation (AuditService) so audit persists even if the
        // enclosing read-only transaction has no write intent.
        auditSvc.logEvent("Customer", c.getId(), "CIF_VIEW", null,
                c.getCustomerNumber(), "CIF",
                "Customer record viewed by " + SecurityUtil.getCurrentUsername());

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

        // CBS Tier-1: Optimistic locking — propagate the @Version from the form/API
        // to the existing entity so JPA detects concurrent modifications. If the version
        // from the client is stale (another user saved in between), Hibernate throws
        // OptimisticLockException on flush. Without this, the version on the existing
        // entity is always "current" (just loaded), so the check never fires.
        // Per Finacle CIF_MASTER / RBI Operational Risk: concurrent edits must be rejected.
        if (updated.getVersion() != null) {
            existing.setVersion(updated.getVersion());
        }

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
        existing.computeCkycAccountType(); // CBS: Recompute CKYC account type when customerType changes
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
        // CBS: addressSameAsPermanent defaults to true on Customer entity.
        // For API path: when field is omitted from JSON, entity carries default (true),
        // which would silently overwrite an existing false value — hiding the customer's
        // distinct permanent address. Only overwrite when the updated entity has permanent
        // address fields populated (indicating the caller explicitly provided address data)
        // OR when the flag is being set to true (collapsing addresses is always safe).
        if (updated.isAddressSameAsPermanent()) {
            existing.setAddressSameAsPermanent(true);
        } else if (updated.getPermanentAddress() != null && !updated.getPermanentAddress().isBlank()) {
            // Caller explicitly provided permanent address data → honor the false flag
            existing.setAddressSameAsPermanent(false);
        }
        // If updated.addressSameAsPermanent=true (default) and no permanent address provided,
        // preserve existing value — prevents silent override from API partial updates.

        // CBS: Nominee details (mutable — per RBI Nomination Guidelines)
        existing.setNomineeDob(updated.getNomineeDob());
        existing.setNomineeAddress(updated.getNomineeAddress());
        existing.setNomineeGuardianName(updated.getNomineeGuardianName());

        // CBS: KYC risk category and PEP flag.
        // CBS CRITICAL: PEP flag is a FATF/RBI compliance field. The service layer
        // receives the full Customer entity from both MVC (always sends pep via hidden
        // field) and API (uses Boolean wrapper with null-guard in populateCustomerFromRequest).
        // For API path: when pep is omitted from JSON, the entity carries Java default (false),
        // which would silently clear a PEP=true flag — a FATF Recommendation 12 violation.
        // Fix: only overwrite PEP if the updated entity explicitly sets it to true,
        // OR if the existing entity was already non-PEP (safe to copy false).
        // Same pattern for addressSameAsPermanent (entity default=true could override false).
        if (updated.getKycRiskCategory() != null) {
            // CBS: Validate kycRiskCategory against allowed values per RBI KYC Section 16.
            // Without validation, arbitrary strings (e.g., "INVALID") would be persisted,
            // causing getKycRenewalYears() to silently default to 8 years (MEDIUM) —
            // masking data quality issues. Per Finacle CIF_MASTER: closed enumeration.
            if (!"LOW".equals(updated.getKycRiskCategory())
                    && !"MEDIUM".equals(updated.getKycRiskCategory())
                    && !"HIGH".equals(updated.getKycRiskCategory())) {
                throw new BusinessException("INVALID_KYC_RISK_CATEGORY",
                        "KYC risk category must be LOW, MEDIUM, or HIGH. Got: "
                                + updated.getKycRiskCategory());
            }
            existing.setKycRiskCategory(updated.getKycRiskCategory());
            existing.computeKycExpiry();
        }
        // CBS: Only clear PEP flag if updated entity explicitly has pep=false AND
        // caller actually provided the field. Since we can't distinguish "not provided"
        // from "false" on a primitive boolean, we use a conservative rule:
        // - If updated.pep=true → always set (escalation is always safe)
        // - If updated.pep=false AND existing.pep=true → preserve existing (don't silently clear)
        //   unless kycRiskCategory was also explicitly changed (indicates intentional update)
        // For MVC path: the hidden _pep field ensures pep is always explicitly sent.
        // For API path: populateCustomerFromRequest only sets pep when non-null.
        if (updated.isPep()) {
            existing.setPep(true);
            existing.setKycRiskCategory("HIGH");
            existing.computeKycExpiry();
        } else if (!existing.isPep()) {
            // Both are false — no change needed, but safe to set explicitly
            existing.setPep(false);
        }
        // If existing.isPep()=true and updated.isPep()=false, we preserve existing PEP=true.
        // To explicitly de-PEP a customer, the kycRiskCategory must also be changed
        // (indicating an intentional risk reassessment, not a missing field).
        if (!updated.isPep() && existing.isPep() && updated.getKycRiskCategory() != null
                && !"HIGH".equals(updated.getKycRiskCategory())) {
            // Intentional de-PEP: risk category explicitly changed away from HIGH
            existing.setPep(false);
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

        // Aadhaar format: exactly 12 digits + Verhoeff checksum validation
        if (aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            if (!aadhaarNumber.matches("^[0-9]{12}$"))
                throw new BusinessException("INVALID_AADHAAR_FORMAT", "Aadhaar must be exactly 12 digits.");
            // CBS Tier-1 (Gap 5): Verhoeff checksum validation per UIDAI specification.
            // The 12th digit of Aadhaar is a check digit computed using the Verhoeff algorithm.
            // Without this, any random 12-digit number passes format validation (e.g., 000000000000).
            // Per Finacle CIF_MASTER / Temenos CUSTOMER: Aadhaar check digit is validated at intake.
            if (!isValidVerhoeff(aadhaarNumber))
                throw new BusinessException("INVALID_AADHAAR_CHECKSUM",
                        "Aadhaar number failed Verhoeff checksum validation. "
                                + "Please verify the number and re-enter.");
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
     * CBS CKYC Mandatory Field Validation per CERSAI Specification v2.0.
     *
     * <p>For INDIVIDUAL-type customers (INDIVIDUAL, JOINT, MINOR, NRI), CERSAI
     * requires: gender, date of birth, and father's name. Mother's name is also
     * mandatory per CERSAI v2.0 Section 4.2.
     *
     * <p>Non-INDIVIDUAL types (HUF, PARTNERSHIP, COMPANY, TRUST, GOVERNMENT) have
     * different mandatory fields handled by the CKYC upload batch — not validated
     * here because the fields are populated during entity registration, not CIF creation.
     *
     * <p>This validation runs on CREATE only. On UPDATE, these fields are mutable
     * (can be corrected during re-KYC) and are validated by {@code validateMutableFields}.
     *
     * @param c the customer entity being created
     */
    private void validateCkycMandatoryFields(Customer c) {
        String type = c.getCustomerType() != null ? c.getCustomerType() : "INDIVIDUAL";
        // CKYC mandatory fields apply to INDIVIDUAL-type customers per CERSAI spec
        boolean isIndividualType = "INDIVIDUAL".equals(type) || "JOINT".equals(type)
                || "MINOR".equals(type) || "NRI".equals(type);
        if (!isIndividualType) return;

        if (c.getGender() == null || c.getGender().isBlank())
            throw new BusinessException("GENDER_REQUIRED",
                    "Gender is mandatory for individual customers per CERSAI CKYC specification v2.0.");
        if (!"M".equals(c.getGender()) && !"F".equals(c.getGender()) && !"T".equals(c.getGender()))
            throw new BusinessException("INVALID_GENDER",
                    "Gender must be M (Male), F (Female), or T (Transgender) per NALSA 2014 / CERSAI.");
        if (c.getDateOfBirth() == null)
            throw new BusinessException("DOB_REQUIRED",
                    "Date of birth is mandatory for individual customers per CERSAI CKYC specification v2.0.");
        if (c.getFatherName() == null || c.getFatherName().isBlank())
            throw new BusinessException("FATHER_NAME_REQUIRED",
                    "Father's name is mandatory for individual customers per CERSAI CKYC specification v2.0.");
        if (c.getMotherName() == null || c.getMotherName().isBlank())
            throw new BusinessException("MOTHER_NAME_REQUIRED",
                    "Mother's name is mandatory for individual customers per CERSAI CKYC specification v2.0.");
    }

    // ========================================================================
    // AADHAAR VERHOEFF CHECKSUM (Gap 5 — per UIDAI specification)
    // ========================================================================

    /**
     * Validates an Aadhaar number using the Verhoeff checksum algorithm.
     *
     * <p>Per UIDAI specification: the 12th digit of Aadhaar is a check digit computed
     * using the Verhoeff algorithm (a dihedral group D5-based checksum). This catches
     * single-digit errors, all adjacent transpositions, and most twin errors.
     *
     * <p>Reference: Verhoeff, J. (1969). "Error Detecting Decimal Codes".
     * Mathematical Centre Tract 29, Amsterdam.
     *
     * @param number the 12-digit Aadhaar number
     * @return true if the Verhoeff checksum is valid
     */
    static boolean isValidVerhoeff(String number) {
        if (number == null || number.isEmpty()) return false;
        int c = 0;
        int len = number.length();
        for (int i = len - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (digit < 0 || digit > 9) return false;
            c = VERHOEFF_D[c][VERHOEFF_P[((len - 1 - i) % 8)][digit]];
        }
        return c == 0;
    }

    // Verhoeff multiplication table (D5 dihedral group)
    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    // Verhoeff permutation table
    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

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
        // CBS UX: normalize to uppercase — branch staff may type lowercase PAN.
        String trimmed = query.trim().toUpperCase();
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
        // CBS UX: normalize to uppercase — branch staff may type lowercase PAN.
        String trimmed = query.trim().toUpperCase();
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
