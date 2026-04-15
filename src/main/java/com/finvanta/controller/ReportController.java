package com.finvanta.controller;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Reports Controller per RBI/Finacle/Temenos reporting standards.
 *
 * Provides:
 * 1. DPD Report — Days Past Due distribution across all accounts
 * 2. IRAC Report — Asset classification per RBI IRAC norms (Standard/SMA/NPA)
 * 3. Provision Report — Provisioning adequacy per RBI norms
 *
 * Per RBI audit requirements, these reports must be available daily
 * and must reflect the current business date's position.
 *
 * CBS Tier-1 Branch Isolation:
 * CHECKER sees only their branch's loan accounts in reports.
 * ADMIN/AUDITOR sees consolidated (all branches) per Finacle BRANCH_CONTEXT.
 */
@Controller
@RequestMapping("/reports")
public class ReportController {

    private final LoanAccountRepository accountRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final BusinessDateService businessDateService;

    public ReportController(LoanAccountRepository accountRepository,
            DepositAccountRepository depositAccountRepository,
            BusinessDateService businessDateService) {
        this.accountRepository = accountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.businessDateService = businessDateService;
    }

    /**
     * Returns branch-scoped active loan accounts per Finacle BRANCH_CONTEXT.
     * ADMIN/AUDITOR: all accounts (consolidated view).
     * CHECKER/MAKER: only accounts at their home branch.
     */
    private List<LoanAccount> getBranchScopedAccounts() {
        String tenantId = TenantContext.getCurrentTenant();
        if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
            return accountRepository.findAllActiveAccounts(tenantId);
        }
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId != null) {
            return accountRepository.findByTenantIdAndBranchId(tenantId, branchId).stream()
                    .filter(a -> !a.getStatus().isTerminal())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * DPD Distribution Report — shows count and outstanding by DPD buckets.
     * Buckets: 0 DPD, 1-30, 31-60, 61-90, 91-180, 181-365, 366-1095, >1095
     */
    @GetMapping("/dpd")
    public ModelAndView dpdReport() {
        List<LoanAccount> accounts = getBranchScopedAccounts();

        // DPD buckets per RBI Early Warning + IRAC
        int[][] buckets = {
            {0, 0}, {1, 30}, {31, 60}, {61, 90}, {91, 180}, {181, 365}, {366, 1095}, {1096, Integer.MAX_VALUE}
        };
        String[] bucketLabels = {
            "0 DPD (Current)",
            "1-30 DPD (SMA-0)",
            "31-60 DPD (SMA-1)",
            "61-90 DPD (SMA-2)",
            "91-180 DPD (Sub-Standard)",
            "181-365 DPD (Sub-Standard)",
            "366-1095 DPD (Doubtful)",
            ">1095 DPD (Loss)"
        };

        List<Map<String, Object>> dpdData = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            int min = buckets[i][0];
            int max = buckets[i][1];
            List<LoanAccount> bucketAccounts = accounts.stream()
                    .filter(a -> a.getDaysPastDue() >= min && a.getDaysPastDue() <= max)
                    .collect(Collectors.toList());

            BigDecimal totalOutstanding = bucketAccounts.stream()
                    .map(LoanAccount::getOutstandingPrincipal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalProvisioning = bucketAccounts.stream()
                    .map(LoanAccount::getProvisioningAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", bucketLabels[i]);
            row.put("count", bucketAccounts.size());
            row.put("outstanding", totalOutstanding);
            row.put("provisioning", totalProvisioning);
            dpdData.add(row);
        }

        ModelAndView mav = new ModelAndView("reports/dpd");
        mav.addObject("dpdData", dpdData);
        mav.addObject("totalAccounts", accounts.size());
        mav.addObject("businessDate", getBusinessDate());
        return mav;
    }

    /**
     * IRAC Classification Report — asset quality breakdown per RBI norms.
     * Categories: Standard, SMA-0/1/2, NPA Sub-Standard, NPA Doubtful, NPA Loss, Restructured
     */
    @GetMapping("/irac")
    public ModelAndView iracReport() {
        List<LoanAccount> accounts = getBranchScopedAccounts();

        Map<String, List<LoanAccount>> byStatus = accounts.stream()
                .collect(Collectors.groupingBy(a -> a.getStatus().name()));

        List<Map<String, Object>> iracData = new ArrayList<>();
        for (LoanStatus status : LoanStatus.values()) {
            if (status.isTerminal()) continue;
            List<LoanAccount> statusAccounts = byStatus.getOrDefault(status.name(), Collections.emptyList());
            if (statusAccounts.isEmpty() && status != LoanStatus.ACTIVE) continue;

            BigDecimal totalOutstanding = statusAccounts.stream()
                    .map(LoanAccount::getOutstandingPrincipal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalProvisioning = statusAccounts.stream()
                    .map(LoanAccount::getProvisioningAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", status.name());
            row.put("category", getIracCategory(status));
            row.put("count", statusAccounts.size());
            row.put("outstanding", totalOutstanding);
            row.put("provisioning", totalProvisioning);
            row.put("isNpa", status.isNpa());
            iracData.add(row);
        }

        BigDecimal totalNpaOutstanding = accounts.stream()
                .filter(a -> a.getStatus().isNpa())
                .map(LoanAccount::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOutstanding =
                accounts.stream().map(LoanAccount::getOutstandingPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);

        ModelAndView mav = new ModelAndView("reports/irac");
        mav.addObject("iracData", iracData);
        mav.addObject("totalAccounts", accounts.size());
        mav.addObject("totalOutstanding", totalOutstanding);
        mav.addObject("totalNpaOutstanding", totalNpaOutstanding);
        mav.addObject(
                "npaRatio",
                totalOutstanding.compareTo(BigDecimal.ZERO) > 0
                        ? totalNpaOutstanding
                                .multiply(BigDecimal.valueOf(100))
                                .divide(totalOutstanding, 2, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
        mav.addObject("businessDate", getBusinessDate());
        return mav;
    }

    /**
     * Provision Adequacy Report — provisioning by asset classification.
     */
    @GetMapping("/provision")
    public ModelAndView provisionReport() {
        List<LoanAccount> accounts = getBranchScopedAccounts();

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalProvisioning = BigDecimal.ZERO;

        List<Map<String, Object>> provisionData = new ArrayList<>();
        for (LoanAccount acc : accounts) {
            if (acc.getProvisioningAmount().compareTo(BigDecimal.ZERO) > 0
                    || acc.getStatus().isNpa()
                    || acc.getStatus().isSma()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accountNumber", acc.getAccountNumber());
                row.put("customerName", acc.getCustomer().getFullName());
                row.put("status", acc.getStatus().name());
                row.put("dpd", acc.getDaysPastDue());
                row.put("outstanding", acc.getOutstandingPrincipal());
                row.put("provisioning", acc.getProvisioningAmount());
                row.put(
                        "provisionRate",
                        acc.getOutstandingPrincipal().compareTo(BigDecimal.ZERO) > 0
                                ? acc.getProvisioningAmount()
                                        .multiply(BigDecimal.valueOf(100))
                                        .divide(acc.getOutstandingPrincipal(), 2, java.math.RoundingMode.HALF_UP)
                                : BigDecimal.ZERO);
                provisionData.add(row);
            }
            totalOutstanding = totalOutstanding.add(acc.getOutstandingPrincipal());
            totalProvisioning = totalProvisioning.add(acc.getProvisioningAmount());
        }

        ModelAndView mav = new ModelAndView("reports/provision");
        mav.addObject("provisionData", provisionData);
        mav.addObject("totalOutstanding", totalOutstanding);
        mav.addObject("totalProvisioning", totalProvisioning);
        mav.addObject(
                "provisionCoverageRatio",
                totalOutstanding.compareTo(BigDecimal.ZERO) > 0
                        ? totalProvisioning
                                .multiply(BigDecimal.valueOf(100))
                                .divide(totalOutstanding, 2, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
        mav.addObject("businessDate", getBusinessDate());
        return mav;
    }

    /**
     * CBS Unclaimed Deposits Report per RBI UDGAM Direction 2024.
     * Per RBI: banks must identify and report accounts with no customer-initiated
     * transaction for 10+ years (INOPERATIVE status) with non-zero balance.
     * These are "unclaimed deposits" that must be reported to RBI UDGAM portal.
     *
     * Report includes: account number, customer details, last transaction date,
     * balance, branch — all fields required by RBI UDGAM submission format.
     *
     * CSV export available for regulatory submission.
     */
    @GetMapping("/udgam")
    public ModelAndView udgamReport() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("reports/udgam");
        List<DepositAccount> unclaimed = depositAccountRepository.findUnclaimedDeposits(tenantId);
        BigDecimal totalUnclaimed = depositAccountRepository.sumUnclaimedDepositBalance(tenantId);

        mav.addObject("unclaimedAccounts", unclaimed);
        mav.addObject("totalUnclaimed", totalUnclaimed);
        mav.addObject("totalCount", unclaimed.size());
        mav.addObject("businessDate", getBusinessDate());
        mav.addObject("pageTitle", "Unclaimed Deposits (RBI UDGAM)");
        return mav;
    }

    /**
     * CBS UDGAM CSV Export for RBI submission.
     * Per RBI UDGAM Direction 2024: regulatory submission in machine-readable format.
     */
    @GetMapping("/udgam/export")
    public ResponseEntity<byte[]> exportUdgam() {
        String tenantId = TenantContext.getCurrentTenant();
        List<DepositAccount> unclaimed = depositAccountRepository.findUnclaimedDeposits(tenantId);

        StringBuilder csv = new StringBuilder();
        csv.append("Account Number,Customer CIF,Customer Name,Account Type,Branch Code,");
        csv.append("Balance (INR),Last Transaction Date,Opened Date,Dormant Since,Status\n");
        for (DepositAccount da : unclaimed) {
            csv.append(da.getAccountNumber()).append(',');
            csv.append(da.getCustomer().getCustomerNumber()).append(',');
            csv.append('"').append(da.getCustomer().getFullName().replace("\"", "\"\"")).append("\",");
            csv.append(da.getAccountType()).append(',');
            csv.append(da.getBranch().getBranchCode()).append(',');
            csv.append(da.getLedgerBalance()).append(',');
            csv.append(da.getLastTransactionDate() != null ? da.getLastTransactionDate() : "").append(',');
            csv.append(da.getOpenedDate() != null ? da.getOpenedDate() : "").append(',');
            csv.append(da.getDormantDate() != null ? da.getDormantDate() : "").append(',');
            csv.append(da.getAccountStatus());
            csv.append('\n');
        }

        String filename = "UDGAM_Unclaimed_Deposits_" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String getIracCategory(LoanStatus status) {
        return switch (status) {
            case ACTIVE -> "Standard";
            case SMA_0 -> "SMA-0 (Early Warning)";
            case SMA_1 -> "SMA-1 (Early Warning)";
            case SMA_2 -> "SMA-2 (Early Warning)";
            case NPA_SUBSTANDARD -> "NPA Sub-Standard";
            case NPA_DOUBTFUL -> "NPA Doubtful";
            case NPA_LOSS -> "NPA Loss";
            case RESTRUCTURED -> "Restructured";
            default -> status.name();
        };
    }

    private LocalDate getBusinessDate() {
        try {
            return businessDateService.getCurrentBusinessDate();
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
