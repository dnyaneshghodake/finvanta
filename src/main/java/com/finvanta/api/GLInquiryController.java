package com.finvanta.api;

import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS GL Inquiry REST API per Finacle GL_INQ / Temenos IRIS GL.
 *
 * Read-only inquiry endpoints for GL balances, chart of accounts,
 * and trial balance. Used by:
 * - Regulatory reporting integrations (XBRL, RBI OSMOS)
 * - External audit systems
 * - Management dashboards
 * - Account Aggregator (AA) framework
 *
 * Per RBI IT Governance: GL data is read-only via API.
 * GL mutations happen ONLY through TransactionEngine (double-entry).
 *
 * CBS Role Matrix:
 *   CHECKER/ADMIN → GL inquiry (financial data)
 *   AUDITOR       → GL inquiry (read-only audit access)
 */
@RestController
@RequestMapping("/api/v1/gl")
public class GLInquiryController {

    private final GLMasterRepository glRepo;

    public GLInquiryController(GLMasterRepository glRepo) {
        this.glRepo = glRepo;
    }

    /** Get GL balance by code. */
    @GetMapping("/{glCode}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<GlResponse>>
            getGlBalance(@PathVariable String glCode) {
        String tid = TenantContext.getCurrentTenant();
        GLMaster gl = glRepo
                .findByTenantIdAndGlCode(tid, glCode)
                .orElseThrow(() -> new BusinessException(
                        "GL_NOT_FOUND", glCode));
        return ResponseEntity.ok(ApiResponse.success(
                GlResponse.from(gl)));
    }

    /** Chart of Accounts — all active GLs ordered by code. */
    @GetMapping("/chart-of-accounts")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<GlResponse>>>
            getChartOfAccounts() {
        String tid = TenantContext.getCurrentTenant();
        var gls = glRepo.findAllActiveOrderByCode(tid);
        var items = gls.stream()
                .map(GlResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    /** Trial Balance — all postable GLs with balances. */
    @GetMapping("/trial-balance")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<TrialBalanceResponse>>
            getTrialBalance() {
        String tid = TenantContext.getCurrentTenant();
        var gls = glRepo.findAllPostableAccounts(tid);
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        var items = new java.util.ArrayList<GlResponse>();
        for (GLMaster gl : gls) {
            items.add(GlResponse.from(gl));
            totalDebit = totalDebit.add(gl.getDebitBalance());
            totalCredit = totalCredit.add(
                    gl.getCreditBalance());
        }
        BigDecimal variance = totalDebit.subtract(
                totalCredit);
        return ResponseEntity.ok(ApiResponse.success(
                new TrialBalanceResponse(
                        totalDebit, totalCredit, variance,
                        variance.signum() == 0,
                        items.size(), items)));
    }

    /** GLs by account type (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE). */
    @GetMapping("/type/{accountType}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<GlResponse>>>
            getByType(
                    @PathVariable String accountType) {
        String tid = TenantContext.getCurrentTenant();
        GLAccountType type =
                GLAccountType.valueOf(accountType);
        var gls = glRepo.findPostableByType(tid, type);
        var items = gls.stream()
                .map(GlResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Response DTOs ===

    public record GlResponse(
            String glCode, String glName,
            String accountType,
            BigDecimal debitBalance,
            BigDecimal creditBalance,
            BigDecimal netBalance,
            boolean headerAccount,
            String parentGlCode,
            Integer glLevel) {
        static GlResponse from(GLMaster gl) {
            return new GlResponse(
                    gl.getGlCode(), gl.getGlName(),
                    gl.getAccountType() != null
                            ? gl.getAccountType().name()
                            : null,
                    gl.getDebitBalance(),
                    gl.getCreditBalance(),
                    gl.getNetBalance(),
                    gl.isHeaderAccount(),
                    gl.getParentGlCode(),
                    gl.getGlLevel());
        }
    }

    public record TrialBalanceResponse(
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal variance,
            boolean balanced,
            int accountCount,
            List<GlResponse> accounts) {}
}
