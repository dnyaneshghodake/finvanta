package com.finvanta.api;

import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.service.BranchService;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Branch REST API per Finacle BRNINQ / Temenos COMPANY.ENQUIRY.
 *
 * <p>Thin orchestration layer over {@link BranchService} — no business logic here.
 * All validation (branch code format, duplicate check, IFSC format, HO uniqueness,
 * hierarchy invariants) and audit trail reside in BranchService.
 *
 * <p>Per Finacle SOL architecture / RBI Banking Regulation Act 1949 §23:
 * every branch must be RBI-licensed with a unique IFSC code. The branch
 * hierarchy (HO → ZO → RO → BRANCH) determines reporting lines,
 * inter-branch settlement flows, and holiday applicability.
 *
 * <p>CBS Role Matrix for Branches:
 * <ul>
 *   <li>MAKER/CHECKER/ADMIN → inquiry (list, search, view)</li>
 *   <li>ADMIN only → create, update (structural changes to branch network)</li>
 * </ul>
 *
 * <p>Used by the Next.js BFF for:
 * <ul>
 *   <li>HO user branch selector (login COC has branch=null for HO users)</li>
 *   <li>Branch context display in the header bar</li>
 *   <li>Admin branch management screens</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/branches")
public class BranchApiController {

    private final BranchService branchService;
    private final BranchRepository branchRepository;

    public BranchApiController(
            BranchService branchService,
            BranchRepository branchRepository) {
        this.branchService = branchService;
        this.branchRepository = branchRepository;
    }

    // === Inquiry ===

    /**
     * List all active branches for the tenant.
     * Per Finacle BRNINQ: used by HO user branch selector and admin screens.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<BranchResponse>>>
            listBranches() {
        var branches = branchService.listActiveBranches();
        var items = branches.stream()
                .map(BranchResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Get branch detail by ID.
     * Per Finacle SOL_INQ: includes hierarchy context (parent, zone, region).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<BranchResponse>>
            getBranch(@PathVariable Long id) {
        Branch branch = branchService.getBranch(id);
        return ResponseEntity.ok(ApiResponse.success(
                BranchResponse.from(branch)));
    }

    /**
     * Search branches by code, name, IFSC, city, zone, region, or type.
     * Per Finacle BRNINQ / RBI Inspection Manual: inspectors request data
     * by IFSC or region during examination.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<BranchResponse>>>
            searchBranches(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        List<Branch> branches;
        if (q != null && q.trim().length() >= 2) {
            branches = branchRepository.searchBranches(
                    tenantId, q.trim());
        } else {
            branches = branchService.listActiveBranches();
        }
        var items = branches.stream()
                .map(BranchResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * List operational branches only (type=BRANCH, excludes HO/ZO/RO).
     * Per Finacle SOL: only operational branches can have customers/accounts.
     * Used by account-opening forms and branch-assignment dropdowns.
     */
    @GetMapping("/operational")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<BranchResponse>>>
            listOperationalBranches() {
        String tenantId = TenantContext.getCurrentTenant();
        var branches = branchRepository
                .findAllOperationalBranches(tenantId);
        var items = branches.stream()
                .map(BranchResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Mutations (ADMIN only) ===

    /**
     * Create a new branch. ADMIN only.
     * Per RBI Banking Regulation Act 1949 §23: branch creation is a
     * controlled operation requiring regulatory approval.
     * All validation delegated to BranchService.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BranchResponse>>
            createBranch(
                    @Valid @RequestBody CreateBranchRequest req) {
        Branch branch = new Branch();
        branch.setBranchCode(req.branchCode());
        branch.setBranchName(req.branchName());
        branch.setBranchType(
                com.finvanta.domain.enums.BranchType
                        .valueOf(req.branchType()));
        branch.setIfscCode(req.ifscCode());
        branch.setAddress(req.address());
        branch.setCity(req.city());
        branch.setState(req.state());
        branch.setPinCode(req.pinCode());
        branch.setRegion(req.region());
        branch.setZoneCode(req.zoneCode());
        branch.setRegionCode(req.regionCode());

        if (req.parentBranchId() != null) {
            Branch parent = branchService.getBranch(
                    req.parentBranchId());
            branch.setParentBranch(parent);
        }

        Branch saved = branchService.createBranch(branch);
        return ResponseEntity.ok(ApiResponse.success(
                BranchResponse.from(saved),
                "Branch created: " + saved.getBranchCode()));
    }

    /**
     * Update a branch. ADMIN only.
     * Per Finacle SOL_MASTER: branchCode and branchType are IMMUTABLE
     * after creation. Only operational fields (name, IFSC, address,
     * zone/region codes) can be updated.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BranchResponse>>
            updateBranch(@PathVariable Long id,
                    @Valid @RequestBody UpdateBranchRequest req) {
        Branch updated = new Branch();
        updated.setBranchName(req.branchName());
        updated.setIfscCode(req.ifscCode());
        updated.setAddress(req.address());
        updated.setCity(req.city());
        updated.setState(req.state());
        updated.setPinCode(req.pinCode());
        updated.setRegion(req.region());
        updated.setZoneCode(req.zoneCode());
        updated.setRegionCode(req.regionCode());

        Branch saved = branchService.updateBranch(id, updated);
        return ResponseEntity.ok(ApiResponse.success(
                BranchResponse.from(saved),
                "Branch updated: " + saved.getBranchCode()));
    }

    // === Request DTOs ===

    public record CreateBranchRequest(
            @NotBlank String branchCode,
            @NotBlank String branchName,
            @NotBlank String branchType,
            String ifscCode,
            String address,
            String city,
            String state,
            String pinCode,
            String region,
            String zoneCode,
            String regionCode,
            Long parentBranchId) {}

    public record UpdateBranchRequest(
            @NotBlank String branchName,
            String ifscCode,
            String address,
            String city,
            String state,
            String pinCode,
            String region,
            String zoneCode,
            String regionCode) {}

    // === Response DTOs ===

    /**
     * Branch response DTO per Finacle SOL_INQ.
     * No JPA entity exposure. Includes hierarchy context for UI rendering.
     */
    public record BranchResponse(
            Long id,
            String branchCode,
            String branchName,
            String branchType,
            String ifscCode,
            String address,
            String city,
            String state,
            String pinCode,
            String region,
            String zoneCode,
            String regionCode,
            boolean headOffice,
            boolean active,
            String parentBranchCode,
            String createdAt) {
        static BranchResponse from(Branch b) {
            return new BranchResponse(
                    b.getId(),
                    b.getBranchCode(),
                    b.getBranchName(),
                    b.getBranchType() != null
                            ? b.getBranchType().name() : null,
                    b.getIfscCode(),
                    b.getAddress(),
                    b.getCity(),
                    b.getState(),
                    b.getPinCode(),
                    b.getRegion(),
                    b.getZoneCode(),
                    b.getRegionCode(),
                    b.isHO(),
                    b.isActive(),
                    b.getParentBranch() != null
                            ? b.getParentBranch().getBranchCode()
                            : null,
                    b.getCreatedAt() != null
                            ? b.getCreatedAt().toString()
                            : null);
        }
    }
}
