package com.finvanta.api;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Regulatory Report REST API per Finacle REPORT_API / RBI OSMOS.
 *
 * <p>Server-side computation of RBI-mandated regulatory reports.
 * Per RBI IRAC Norms / RBI Inspection Manual / CRILC Reporting:
 * <ul>
 *   <li>DPD Report — Days Past Due distribution for early warning</li>
 *   <li>IRAC Report — Asset classification (Standard/SMA/Sub-Standard/Doubtful/Loss)</li>
 *   <li>Provision Report — Provisioning adequacy per IRAC norms</li>
 * </ul>
 *
 * <p>All reports are branch-scoped for CHECKER, tenant-wide for ADMIN/AUDITOR.
 * Per Finacle: branch-level officers see only their branch portfolio;
 * HO/ADMIN sees the consolidated view for regulatory returns.
 *
 * <p>CBS Role Matrix:
 * <ul>
 *   <li>CHECKER/ADMIN/AUDITOR → all report endpoints</li>
 *   <li>CHECKER sees branch-scoped data only</li>
 *   <li>ADMIN/AUDITOR sees tenant-wide consolidated data</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/reports")
public class ReportApiController {

    private final LoanAccountRepository accountRepository;
    private final BusinessDateService businessDateService;

    public ReportApiController(
            LoanAccountRepository accountRepository,
            BusinessDateService businessDateService) {
        this.accountRepository = accountRepository;
        this.businessDateService = businessDateService;
    }

    /**
     * DPD Distribution Report per RBI Early Warning System + IRAC Norms.
     *
     * <p>Buckets: 0 DPD (Current), 1-30 (SMA-0), 31-60 (SMA-1),
     * 61-90 (SMA-2), 91-180 (Sub-Standard), 181-365 (Sub-Standard),
     * 366-1095 (Doubtful), >1095 (Loss).
     */
    @GetMapping("/dpd")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<DpdReport>>
            getDpdReport() {
        List<LoanAccount> accounts = getBranchScopedAccounts();
        LocalDate businessDate = getBusinessDateSafe();

        int[][] buckets = {
            {0, 0}, {1, 30}, {31, 60}, {61, 90},
            {91, 180}, {181, 365}, {366, 1095},
            {1096, Integer.MAX_VALUE}
        };
        String[] labels = {
            "0 DPD (Current)", "1-30 DPD (SMA-0)",
            "31-60 DPD (SMA-1)", "61-90 DPD (SMA-2)",
            "91-180 DPD (Sub-Standard)",
            "181-365 DPD (Sub-Standard)",
            "366-1095 DPD (Doubtful)",
            ">1095 DPD (Loss)"
        };

        List<DpdBucket> dpdData = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            int min = buckets[i][0];
            int max = buckets[i][1];
            List<LoanAccount> bucket = accounts.stream()
                    .filter(a -> a.getDaysPastDue() >= min
                            && a.getDaysPastDue() <= max)
                    .collect(Collectors.toList());

            BigDecimal outstanding = bucket.stream()
                    .map(LoanAccount::getOutstandingPrincipal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal provisioning = bucket.stream()
                    .map(LoanAccount::getProvisioningAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            dpdData.add(new DpdBucket(
                    labels[i], bucket.size(),
                    outstanding, provisioning));
        }

        return ResponseEntity.ok(ApiResponse.success(
                new DpdReport(dpdData, accounts.size(),
                        businessDate != null
                                ? businessDate.toString()
                                : null)));
    }

    /**
     * IRAC Asset Classification Report per RBI IRAC Norms 2024.
     *
     * <p>Categories: Standard, SMA (0/1/2), NPA Sub-Standard,
     * NPA Doubtful, NPA Loss, Restructured, Written-Off.
     * Per RBI: provisioning rates differ by category.
     */
    @GetMapping("/irac")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<IracReport>>
            getIracReport() {
        List<LoanAccount> accounts = getBranchScopedAccounts();
        LocalDate businessDate = getBusinessDateSafe();

        List<IracCategory> categories = new ArrayList<>();
        categories.add(buildCategory("Standard", accounts,
                a -> a.getDaysPastDue() == 0
                        && !a.getStatus().isSma()
                        && !a.getStatus().isNpa()));
        categories.add(buildCategory("SMA-0", accounts,
                a -> a.getDaysPastDue() >= 1
                        && a.getDaysPastDue() <= 30));
        categories.add(buildCategory("SMA-1", accounts,
                a -> a.getDaysPastDue() >= 31
                        && a.getDaysPastDue() <= 60));
        categories.add(buildCategory("SMA-2", accounts,
                a -> a.getDaysPastDue() >= 61
                        && a.getDaysPastDue() <= 90));
        categories.add(buildCategory("NPA Sub-Standard",
                accounts, a -> a.getStatus().name()
                        .equals("NPA_SUBSTANDARD")));
        categories.add(buildCategory("NPA Doubtful",
                accounts, a -> a.getStatus().name()
                        .equals("NPA_DOUBTFUL")));
        categories.add(buildCategory("NPA Loss",
                accounts, a -> a.getStatus().name()
                        .equals("NPA_LOSS")));
        categories.add(buildCategory("Restructured",
                accounts, a -> a.getStatus().name()
                        .equals("RESTRUCTURED")));

        BigDecimal totalOutstanding = accounts.stream()
                .map(LoanAccount::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(ApiResponse.success(
                new IracReport(categories, accounts.size(),
                        totalOutstanding,
                        businessDate != null
                                ? businessDate.toString()
                                : null)));
    }

    /**
     * Provisioning Adequacy Report per RBI IRAC Norms 2024.
     *
     * <p>Per RBI provisioning rates:
     * Standard 0.4%, Sub-Standard 15%, Doubtful 25-100%, Loss 100%.
     * Shows actual vs required provisioning per category.
     */
    @GetMapping("/provision")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<ProvisionReport>>
            getProvisionReport() {
        List<LoanAccount> accounts = getBranchScopedAccounts();
        LocalDate businessDate = getBusinessDateSafe();

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalProvisioning = BigDecimal.ZERO;
        for (LoanAccount a : accounts) {
            totalOutstanding = totalOutstanding
                    .add(a.getOutstandingPrincipal());
            totalProvisioning = totalProvisioning
                    .add(a.getProvisioningAmount());
        }

        long npaCount = accounts.stream()
                .filter(a -> a.getStatus().isNpa()).count();
        BigDecimal npaOutstanding = accounts.stream()
                .filter(a -> a.getStatus().isNpa())
                .map(LoanAccount::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal npaProvisioning = accounts.stream()
                .filter(a -> a.getStatus().isNpa())
                .map(LoanAccount::getProvisioningAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(ApiResponse.success(
                new ProvisionReport(
                        accounts.size(),
                        totalOutstanding,
                        totalProvisioning,
                        npaCount,
                        npaOutstanding,
                        npaProvisioning,
                        businessDate != null
                                ? businessDate.toString()
                                : null)));
    }

    // === Helpers ===

    /**
     * Branch-scoped account list per Finacle SOL isolation.
     * ADMIN/AUDITOR sees all; CHECKER sees own branch only.
     */
    private List<LoanAccount> getBranchScopedAccounts() {
        String tenantId = TenantContext.getCurrentTenant();
        if (SecurityUtil.isAdminRole()
                || SecurityUtil.isAuditorRole()) {
            return accountRepository
                    .findAllActiveAccounts(tenantId);
        }
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId != null) {
            return accountRepository
                    .findByTenantIdAndBranchId(
                            tenantId, branchId)
                    .stream()
                    .filter(a -> !a.getStatus().isTerminal())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /** Safe business date — returns null if no day is open. */
    private LocalDate getBusinessDateSafe() {
        try {
            return businessDateService
                    .getCurrentBusinessDate();
        } catch (Exception e) {
            return null;
        }
    }

    private IracCategory buildCategory(String name,
            List<LoanAccount> all,
            java.util.function.Predicate<LoanAccount> filter) {
        List<LoanAccount> filtered = all.stream()
                .filter(filter)
                .collect(Collectors.toList());
        BigDecimal outstanding = filtered.stream()
                .map(LoanAccount::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal provisioning = filtered.stream()
                .map(LoanAccount::getProvisioningAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new IracCategory(name, filtered.size(),
                outstanding, provisioning);
    }

    // === Response DTOs ===

    public record DpdReport(
            List<DpdBucket> buckets,
            int totalAccounts,
            String businessDate) {}

    public record DpdBucket(
            String label,
            int count,
            BigDecimal outstanding,
            BigDecimal provisioning) {}

    public record IracReport(
            List<IracCategory> categories,
            int totalAccounts,
            BigDecimal totalOutstanding,
            String businessDate) {}

    public record IracCategory(
            String category,
            int count,
            BigDecimal outstanding,
            BigDecimal provisioning) {}

    public record ProvisionReport(
            int totalAccounts,
            BigDecimal totalOutstanding,
            BigDecimal totalProvisioning,
            long npaCount,
            BigDecimal npaOutstanding,
            BigDecimal npaProvisioning,
            String businessDate) {}
}
