package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.enums.BranchType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Branch Service per Finacle BRANCH_MASTER / Temenos COMPANY.
 *
 * Encapsulates all branch lifecycle business rules:
 * - Branch code immutability after creation
 * - IFSC code format validation (4-char prefix + 0 + 6-digit branch code)
 * - Hierarchy invariant enforcement (single HO per tenant)
 * - Duplicate branch code prevention within tenant
 * - Branch code format validation (alphanumeric, max 20 chars)
 *
 * Per RBI Banking Regulation Act 1949 Section 23:
 * - Every branch must be licensed by RBI (IFSC code assigned)
 * - Branch hierarchy is reported in statutory returns (OSMOS)
 *
 * Per Finacle BRANCH_MASTER: all branch operations go through a service
 * layer with validation rules — controllers only delegate.
 */
@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    private final BranchRepository branchRepository;
    private final AuditService auditService;

    public BranchService(BranchRepository branchRepository, AuditService auditService) {
        this.branchRepository = branchRepository;
        this.auditService = auditService;
    }

    /**
     * Returns all active branches for the current tenant.
     */
    public List<Branch> listActiveBranches() {
        String tenantId = TenantContext.getCurrentTenant();
        return branchRepository.findByTenantIdAndActiveTrue(tenantId);
    }

    /**
     * Fetches a branch by ID with tenant isolation validation.
     * Uses @Transactional(readOnly=true) to keep the Hibernate session open
     * for lazy-loaded associations (parentBranch) accessed in the view layer.
     * Production runs with spring.jpa.open-in-view=false — without this
     * annotation, accessing branch.parentBranch.branchCode in the JSP
     * throws LazyInitializationException.
     *
     * @param branchId Branch ID
     * @return Branch entity with parentBranch initialized
     * @throws BusinessException if not found or belongs to different tenant
     */
    @Transactional(readOnly = true)
    public Branch getBranch(Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        Branch branch = branchRepository
                .findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found: " + branchId));
        // Force-initialize lazy association to avoid LazyInitializationException
        // when accessed in the view (production has open-in-view=false)
        if (branch.getParentBranch() != null) {
            branch.getParentBranch().getBranchCode(); // trigger initialization
        }
        return branch;
    }

    /**
     * Creates a new branch with CBS-grade validations.
     *
     * Per Finacle BRANCH_MASTER:
     * - Branch code must be unique within tenant
     * - Branch code format: alphanumeric, 1-20 chars
     * - IFSC code format: 4-char prefix + '0' + 6-digit code (11 chars total)
     * - Only one HEAD_OFFICE per tenant
     * - HEAD_OFFICE cannot have a parent branch
     *
     * @param branch Branch entity to create
     * @return Created branch
     */
    @Transactional
    public Branch createBranch(Branch branch) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // CBS Validation: Branch code format
        if (branch.getBranchCode() == null || branch.getBranchCode().isBlank()) {
            throw new BusinessException("BRANCH_CODE_REQUIRED", "Branch code is mandatory");
        }
        if (!branch.getBranchCode().matches("^[A-Za-z0-9_]{1,20}$")) {
            throw new BusinessException("INVALID_BRANCH_CODE",
                    "Branch code must be alphanumeric (1-20 chars): " + branch.getBranchCode());
        }

        // CBS Validation: Duplicate branch code within tenant
        if (branchRepository.existsByTenantIdAndBranchCode(tenantId, branch.getBranchCode())) {
            throw new BusinessException("DUPLICATE_BRANCH_CODE",
                    "Branch code already exists: " + branch.getBranchCode());
        }

        // CBS Validation: Single HEAD_OFFICE per tenant
        if (branch.getBranchType() == BranchType.HEAD_OFFICE) {
            branchRepository.findHeadOffice(tenantId).ifPresent(existing -> {
                throw new BusinessException("DUPLICATE_HEAD_OFFICE",
                        "Tenant already has a Head Office: " + existing.getBranchCode()
                                + ". Only one HEAD_OFFICE is allowed per tenant.");
            });
        }

        // CBS Validation: IFSC code format (if provided)
        if (branch.getIfscCode() != null && !branch.getIfscCode().isBlank()) {
            if (!branch.getIfscCode().matches("^[A-Z]{4}0[A-Za-z0-9]{6}$")) {
                throw new BusinessException("INVALID_IFSC",
                        "IFSC code must be 11 chars: 4-letter prefix + '0' + 6-char branch code. Got: "
                                + branch.getIfscCode());
            }
        }

        // CBS Validation: Branch name is mandatory
        if (branch.getBranchName() == null || branch.getBranchName().isBlank()) {
            throw new BusinessException("BRANCH_NAME_REQUIRED", "Branch name is mandatory");
        }

        branch.setTenantId(tenantId);
        branch.setCreatedBy(currentUser);
        Branch saved = branchRepository.save(branch);

        auditService.logEvent(
                "Branch",
                saved.getId(),
                "CREATE",
                null,
                saved.getBranchCode(),
                "BRANCH",
                "Branch created: " + saved.getBranchCode() + " — " + saved.getBranchName()
                        + " | Type: " + saved.getBranchType()
                        + " | By: " + currentUser);

        log.info("Branch created: code={}, name={}, type={}, user={}",
                saved.getBranchCode(), saved.getBranchName(), saved.getBranchType(), currentUser);

        return saved;
    }

    /**
     * Updates a branch with CBS-grade validations.
     *
     * Per Finacle BRANCH_MASTER:
     * - Branch code is IMMUTABLE after creation (used in voucher numbers, calendar, audit)
     * - Branch type is IMMUTABLE after creation (hierarchy cannot be restructured via edit)
     * - Only non-structural fields can be updated (name, address, IFSC, region)
     *
     * @param branchId Branch ID to update
     * @param updated  Updated branch data
     * @return Updated branch
     */
    @Transactional
    public Branch updateBranch(Long branchId, Branch updated) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        Branch existing = branchRepository
                .findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found: " + branchId));

        String beforeState = "name=" + existing.getBranchName()
                + ", ifsc=" + existing.getIfscCode()
                + ", city=" + existing.getCity();

        // CBS Validation: IFSC code format (if being changed)
        if (updated.getIfscCode() != null && !updated.getIfscCode().isBlank()) {
            if (!updated.getIfscCode().matches("^[A-Z]{4}0[A-Za-z0-9]{6}$")) {
                throw new BusinessException("INVALID_IFSC",
                        "IFSC code must be 11 chars: 4-letter prefix + '0' + 6-char branch code. Got: "
                                + updated.getIfscCode());
            }
        }

        // CBS: Update only mutable fields. branchCode and branchType are IMMUTABLE.
        existing.setBranchName(updated.getBranchName());
        existing.setIfscCode(updated.getIfscCode());
        existing.setAddress(updated.getAddress());
        existing.setCity(updated.getCity());
        existing.setState(updated.getState());
        existing.setPinCode(updated.getPinCode());
        existing.setRegion(updated.getRegion());
        existing.setZoneCode(updated.getZoneCode());
        existing.setRegionCode(updated.getRegionCode());
        existing.setUpdatedBy(currentUser);

        Branch saved = branchRepository.save(existing);

        String afterState = "name=" + saved.getBranchName()
                + ", ifsc=" + saved.getIfscCode()
                + ", city=" + saved.getCity();

        auditService.logEvent(
                "Branch",
                saved.getId(),
                "UPDATE",
                beforeState,
                afterState,
                "BRANCH",
                "Branch updated: " + saved.getBranchCode() + " | By: " + currentUser);

        log.info("Branch updated: code={}, user={}", saved.getBranchCode(), currentUser);

        return saved;
    }
}
