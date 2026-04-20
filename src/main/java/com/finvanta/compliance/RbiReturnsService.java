package com.finvanta.compliance;

import com.finvanta.domain.entity.Tenant;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.TenantRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS RBI Statutory Returns Service per Finacle REPORT_STATUTORY / Temenos EB.REGULATORY.
 *
 * <p>Computes data for mandatory RBI regulatory returns:
 * <ul>
 *   <li><b>DSB (Daily Statement of Balances):</b> Per RBI Act 1934 §27 — daily
 *       submission of key balance sheet items. Penalty: ₹10,000/day for late filing.</li>
 *   <li><b>CRR/SLR Compliance:</b> Per RBI Act 1934 §42/§24 — fortnightly
 *       computation of Net Demand and Time Liabilities (NDTL) and reserve requirements.</li>
 *   <li><b>BSR-1 (Basic Statistical Return):</b> Per RBI — quarterly return on
 *       credit deployment by sector, borrower type, interest rate band.</li>
 * </ul>
 *
 * <p><b>Phase 1:</b> Computes DSB and CRR/SLR data from GL balances and deposit/loan
 * repositories. Actual OSMOS/XBRL file generation and submission will be added in Phase 2.
 *
 * <p><b>Data Sources:</b>
 * <ul>
 *   <li>GL Master (gl_master) — aggregate balances for balance sheet items</li>
 *   <li>Deposit Accounts — NDTL computation (demand + time liabilities)</li>
 *   <li>Loan Accounts — credit deployment analysis for BSR-1</li>
 *   <li>Tenant — CRR/SLR percentages, Tier-1 capital base</li>
 * </ul>
 *
 * <p>Per Finacle REPORT_STATUTORY / Temenos EB.REGULATORY:
 * All statutory returns are tenant-scoped and computed from the GL
 * (single source of truth for financial data).
 *
 * @see com.finvanta.accounting.AccountingService
 */
@Service
public class RbiReturnsService {

    private static final Logger log = LoggerFactory.getLogger(RbiReturnsService.class);

    private final GLMasterRepository glMasterRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final TenantRepository tenantRepository;

    public RbiReturnsService(
            GLMasterRepository glMasterRepository,
            DepositAccountRepository depositAccountRepository,
            LoanAccountRepository loanAccountRepository,
            TenantRepository tenantRepository) {
        this.glMasterRepository = glMasterRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Computes Daily Statement of Balances (DSB) data.
     *
     * <p>Per RBI Act 1934 Section 27: Every banking company must submit
     * a daily statement of its position in India, showing:
     * <ul>
     *   <li>Total demand liabilities (current accounts, savings demand portion)</li>
     *   <li>Total time liabilities (fixed deposits, recurring deposits, savings time portion)</li>
     *   <li>Total assets (loans, investments, cash, balances with banks)</li>
     *   <li>CRR balance maintained with RBI</li>
     *   <li>SLR investments held</li>
     * </ul>
     *
     * @param reportDate The date for which DSB is being computed
     * @return Map containing DSB line items
     */
    @Transactional(readOnly = true)
    public Map<String, Object> computeDsb(LocalDate reportDate) {
        String tenantId = TenantContext.getCurrentTenant();
        Tenant tenant = tenantRepository.findByTenantCode(tenantId).orElse(null);

        // Compute NDTL from deposit accounts
        BigDecimal totalDeposits = depositAccountRepository.calculateTotalDeposits(tenantId);
        // Phase 1: All CASA deposits treated as demand liabilities.
        // Phase 2: Split savings into demand (up to 10L) and time (above 10L) per RBI norms.
        BigDecimal demandLiabilities = totalDeposits;
        BigDecimal timeLiabilities = BigDecimal.ZERO; // FD balances — Phase 2

        BigDecimal ndtl = demandLiabilities.add(timeLiabilities);

        // CRR/SLR requirements
        BigDecimal crrPct = tenant != null && tenant.getCrrPercentage() != null
                ? tenant.getCrrPercentage() : new BigDecimal("4.50");
        BigDecimal slrPct = tenant != null && tenant.getSlrPercentage() != null
                ? tenant.getSlrPercentage() : new BigDecimal("18.00");

        BigDecimal crrRequired = ndtl.multiply(crrPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal slrRequired = ndtl.multiply(slrPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Total advances (loan outstanding)
        BigDecimal totalAdvances = loanAccountRepository.calculateTotalOutstandingPrincipal(tenantId);

        Map<String, Object> dsb = new LinkedHashMap<>();
        dsb.put("reportDate", reportDate.toString());
        dsb.put("tenantCode", tenantId);
        dsb.put("rbiBankCode", tenant != null ? tenant.getRbiBankCode() : null);

        // Liabilities
        dsb.put("demandLiabilities", demandLiabilities);
        dsb.put("timeLiabilities", timeLiabilities);
        dsb.put("ndtl", ndtl);

        // Reserve Requirements
        dsb.put("crrPercentage", crrPct);
        dsb.put("crrRequired", crrRequired);
        dsb.put("crrMaintained", BigDecimal.ZERO); // Phase 2: from nostro/RBI GL
        dsb.put("crrShortfall", crrRequired); // Phase 2: crrRequired - crrMaintained

        dsb.put("slrPercentage", slrPct);
        dsb.put("slrRequired", slrRequired);
        dsb.put("slrMaintained", BigDecimal.ZERO); // Phase 2: from investment GL
        dsb.put("slrShortfall", slrRequired); // Phase 2: slrRequired - slrMaintained

        // Assets
        dsb.put("totalAdvances", totalAdvances);
        dsb.put("totalDeposits", totalDeposits);

        log.info("DSB computed: date={}, ndtl={}, crrReq={}, slrReq={}, advances={}",
                reportDate, ndtl, crrRequired, slrRequired, totalAdvances);

        return dsb;
    }

    /**
     * Computes CRR/SLR compliance position.
     *
     * <p>Per RBI Act 1934:
     * <ul>
     *   <li>Section 42: CRR (Cash Reserve Ratio) — currently 4.50% of NDTL</li>
     *   <li>Section 24: SLR (Statutory Liquidity Ratio) — currently 18.00% of NDTL</li>
     * </ul>
     *
     * <p>CRR must be maintained with RBI on a fortnightly average basis.
     * SLR must be maintained in approved securities (G-Secs, T-Bills, SDLs).
     *
     * @return Map with CRR/SLR position data
     */
    @Transactional(readOnly = true)
    public Map<String, Object> computeCrrSlrPosition() {
        String tenantId = TenantContext.getCurrentTenant();
        Tenant tenant = tenantRepository.findByTenantCode(tenantId).orElse(null);

        BigDecimal totalDeposits = depositAccountRepository.calculateTotalDeposits(tenantId);
        BigDecimal ndtl = totalDeposits; // Phase 1 approximation

        BigDecimal crrPct = tenant != null && tenant.getCrrPercentage() != null
                ? tenant.getCrrPercentage() : new BigDecimal("4.50");
        BigDecimal slrPct = tenant != null && tenant.getSlrPercentage() != null
                ? tenant.getSlrPercentage() : new BigDecimal("18.00");

        BigDecimal crrRequired = ndtl.multiply(crrPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal slrRequired = ndtl.multiply(slrPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("ndtl", ndtl);
        position.put("crrPercentage", crrPct);
        position.put("crrRequired", crrRequired);
        position.put("crrMaintained", BigDecimal.ZERO);
        position.put("crrCompliant", false);
        position.put("slrPercentage", slrPct);
        position.put("slrRequired", slrRequired);
        position.put("slrMaintained", BigDecimal.ZERO);
        position.put("slrCompliant", false);

        return position;
    }
}
