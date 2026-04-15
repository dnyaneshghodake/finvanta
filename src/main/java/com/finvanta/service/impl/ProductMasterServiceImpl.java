package com.finvanta.service.impl;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.domain.enums.ProductStatus;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.ProductMasterService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CBS Product Master Service per Finacle PDDEF. All business logic here. */
@Service
public class ProductMasterServiceImpl implements ProductMasterService {
    private static final Logger log = LoggerFactory.getLogger(ProductMasterServiceImpl.class);
    private final ProductMasterRepository productRepo;
    private final GLMasterRepository glRepo;
    private final LoanAccountRepository loanAccountRepo;
    private final DepositAccountRepository depositAccountRepo;
    private final ProductGLResolver glResolver;
    private final AuditService auditSvc;

    public ProductMasterServiceImpl(ProductMasterRepository productRepo, GLMasterRepository glRepo,
            LoanAccountRepository loanAccountRepo, DepositAccountRepository depositAccountRepo,
            ProductGLResolver glResolver, AuditService auditSvc) {
        this.productRepo = productRepo;
        this.glRepo = glRepo;
        this.loanAccountRepo = loanAccountRepo;
        this.depositAccountRepo = depositAccountRepo;
        this.glResolver = glResolver;
        this.auditSvc = auditSvc;
    }

    @Override
    @Transactional
    public ProductMaster createProduct(ProductMaster p) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        validateProductCode(p.getProductCode());
        if (productRepo.findByTenantIdAndProductCode(tid, p.getProductCode()).isPresent())
            throw new BusinessException("DUPLICATE_PRODUCT", "Product code exists: " + p.getProductCode());
        validateFields(p);
        validateGlCodes(tid, p);
        // CBS CRITICAL: Mass Assignment Protection per OWASP A4 / Finacle PDDEF.
        // Spring MVC @ModelAttribute binds ALL request parameters to entity fields,
        // including BaseEntity fields (id, version). A malicious POST with
        // id=<existing_id>&version=<matching> would cause JPA merge() instead of
        // persist(), overwriting an existing product's GL codes — a financially
        // unsafe operation that could route transactions to wrong GL accounts.
        p.setId(null);
        p.setVersion(null);
        p.setTenantId(tid);
        p.setProductStatus(ProductStatus.ACTIVE);
        p.setActive(true);
        p.setCreatedBy(user);
        p.setUpdatedBy(null);
        ProductMaster saved = productRepo.save(p);
        glResolver.evictCache();
        auditSvc.logEvent("ProductMaster", saved.getId(), "PRODUCT_CREATED", null,
                saved.getProductCode(), "PRODUCT_MASTER",
                "Created: " + saved.getProductCode() + " | " + saved.getProductCategory() + " | By: " + user);
        return saved;
    }

    @Override
    @Transactional
    public ProductMaster updateProduct(Long productId, ProductMaster u) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        ProductMaster e = productRepo.findById(productId).filter(p -> p.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "" + productId));
        if (e.getProductStatus() == ProductStatus.RETIRED)
            throw new BusinessException("PRODUCT_RETIRED", "Retired products cannot be modified.");
        validateFields(u);
        validateGlCodes(tid, u);
        String before = e.getGlLoanAsset() + "|" + e.getMinInterestRate() + "-" + e.getMaxInterestRate();
        e.setProductName(u.getProductName());
        e.setDescription(u.getDescription());
        e.setInterestMethod(u.getInterestMethod());
        e.setInterestType(u.getInterestType());
        e.setMinInterestRate(u.getMinInterestRate());
        e.setMaxInterestRate(u.getMaxInterestRate());
        e.setDefaultPenalRate(u.getDefaultPenalRate());
        e.setMinLoanAmount(u.getMinLoanAmount());
        e.setMaxLoanAmount(u.getMaxLoanAmount());
        e.setMinTenureMonths(u.getMinTenureMonths());
        e.setMaxTenureMonths(u.getMaxTenureMonths());
        e.setRepaymentFrequency(u.getRepaymentFrequency());
        e.setRepaymentAllocation(u.getRepaymentAllocation());
        e.setPrepaymentPenaltyApplicable(u.isPrepaymentPenaltyApplicable());
        e.setProcessingFeePct(u.getProcessingFeePct());
        e.setGlLoanAsset(u.getGlLoanAsset());
        e.setGlInterestReceivable(u.getGlInterestReceivable());
        e.setGlBankOperations(u.getGlBankOperations());
        e.setGlInterestIncome(u.getGlInterestIncome());
        e.setGlFeeIncome(u.getGlFeeIncome());
        e.setGlPenalIncome(u.getGlPenalIncome());
        e.setGlProvisionExpense(u.getGlProvisionExpense());
        e.setGlProvisionNpa(u.getGlProvisionNpa());
        e.setGlWriteOffExpense(u.getGlWriteOffExpense());
        e.setGlInterestSuspense(u.getGlInterestSuspense());
        e.setDefaultBenchmarkName(u.getDefaultBenchmarkName());
        e.setDefaultRateResetFrequency(u.getDefaultRateResetFrequency());
        e.setDefaultSpread(u.getDefaultSpread());
        e.setInterestTieringEnabled(u.isInterestTieringEnabled());
        e.setInterestTieringJson(u.getInterestTieringJson());
        e.setUpdatedBy(user);
        productRepo.save(e);
        glResolver.evictCache();
        String after = e.getGlLoanAsset() + "|" + e.getMinInterestRate() + "-" + e.getMaxInterestRate();
        auditSvc.logEvent("ProductMaster", e.getId(), "PRODUCT_UPDATED", before, after,
                "PRODUCT_MASTER", "Updated: " + e.getProductCode() + " by " + user);
        return e;
    }

    @Override
    @Transactional
    public ProductMaster changeStatus(Long productId, String newStatusStr) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        ProductMaster p = productRepo.findById(productId).filter(x -> x.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "" + productId));
        ProductStatus ns = ProductStatus.fromString(newStatusStr);
        if (ns == null) throw new BusinessException("INVALID_STATUS", "Invalid: " + newStatusStr);
        ProductStatus os = p.getProductStatus();
        validateStatusTransition(os, ns, p.getProductCode());
        p.setProductStatus(ns);
        p.setActive(ns.isOriginationAllowed());
        p.setUpdatedBy(user);
        productRepo.save(p);
        glResolver.evictCache();
        auditSvc.logEvent("ProductMaster", p.getId(), "STATUS_CHANGE", os.name(), ns.name(),
                "PRODUCT_MASTER", p.getProductCode() + ": " + os + " -> " + ns + " by " + user);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductMaster getProduct(Long productId) {
        String tid = TenantContext.getCurrentTenant();
        return productRepo.findById(productId).filter(p -> p.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "" + productId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductMaster> listProducts() {
        return productRepo.findByTenantIdOrderByProductCode(TenantContext.getCurrentTenant());
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveAccounts(Long productId) {
        String tid = TenantContext.getCurrentTenant();
        String code = getProduct(productId).getProductCode();
        // CBS: Use DB-level COUNT queries instead of loading entire account portfolio.
        // Per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG: product active account count
        // is displayed on the product detail page and edit form. Loading all accounts
        // into JVM memory just to count matching rows is an OOM risk for banks with
        // thousands of accounts. JPQL COUNT with WHERE clause pushes filtering to DB.
        long loans = loanAccountRepo.countActiveByProductType(tid, code);
        long deps = depositAccountRepo.countNonClosedByProductCode(tid, code);
        return loans + deps;
    }

    private void validateProductCode(String code) {
        if (code == null || code.isBlank())
            throw new BusinessException("PRODUCT_CODE_REQUIRED", "Product code is mandatory.");
        if (!code.matches("^[A-Z0-9_]{2,50}$"))
            throw new BusinessException("INVALID_PRODUCT_CODE",
                    "Must be 2-50 chars, uppercase alphanumeric + underscore. Got: " + code);
    }

    private void validateFields(ProductMaster p) {
        if (p.getProductName() == null || p.getProductName().isBlank())
            throw new BusinessException("PRODUCT_NAME_REQUIRED", "Product name is mandatory.");
        if (p.getMinInterestRate() != null && p.getMinInterestRate().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException("INVALID_RATE", "Min rate cannot be negative.");
        if (p.getMaxInterestRate() != null && p.getMaxInterestRate().compareTo(new BigDecimal("100")) > 0)
            throw new BusinessException("INVALID_RATE", "Max rate cannot exceed 100%.");
        if (p.getMinInterestRate() != null && p.getMaxInterestRate() != null
                && p.getMinInterestRate().compareTo(p.getMaxInterestRate()) > 0)
            throw new BusinessException("INVALID_RATE_RANGE", "Min rate > max rate.");
        if (p.getDefaultPenalRate() != null && p.getDefaultPenalRate().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException("INVALID_PENAL_RATE", "Penal rate cannot be negative.");
        if (p.getDefaultPenalRate() != null && p.getDefaultPenalRate().compareTo(new BigDecimal("36")) > 0)
            throw new BusinessException("INVALID_PENAL_RATE", "Penal rate exceeds RBI usury ceiling of 36%.");
        if (p.getMinLoanAmount() != null && p.getMinLoanAmount().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException("INVALID_AMOUNT", "Min amount cannot be negative.");
        if (p.getMaxLoanAmount() != null && p.getMaxLoanAmount().signum() > 0
                && p.getMinLoanAmount() != null && p.getMinLoanAmount().compareTo(p.getMaxLoanAmount()) > 0)
            throw new BusinessException("INVALID_AMOUNT_RANGE", "Min amount > max amount.");
        if (p.getMinTenureMonths() != null && p.getMaxTenureMonths() != null
                && p.getMinTenureMonths() > p.getMaxTenureMonths())
            throw new BusinessException("INVALID_TENURE_RANGE", "Min tenure > max tenure.");
    }

    /**
     * CBS GL Validation per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
     *
     * Per Tier-1 CBS (Finacle/Temenos/BNP): loan and deposit products have
     * fundamentally different GL semantics. Loan products use ASSET GLs for
     * principal outstanding; deposit products use LIABILITY GLs for customer
     * deposit balances. Applying loan GL rules to deposit products would reject
     * every valid CASA/FD configuration.
     *
     * Category-aware validation:
     *   LOAN categories (TERM_LOAN, DEMAND_LOAN, OVERDRAFT, CASH_CREDIT):
     *     - glLoanAsset must be ASSET (principal outstanding)
     *     - glInterestReceivable must be ASSET (accrued interest receivable)
     *     - glProvisionNpa must be ASSET (contra-asset for loan loss)
     *     - glInterestIncome must be INCOME (interest earned)
     *     - glProvisionExpense must be EXPENSE (P&L charge for provisioning)
     *     - glWriteOffExpense must be EXPENSE (P&L charge for write-off)
     *     - glInterestSuspense must be LIABILITY (NPA interest parked)
     *
     *   CASA categories (CASA_SAVINGS, CASA_CURRENT):
     *     - glLoanAsset repurposed as Deposit Liability → must be LIABILITY (2010/2020)
     *     - glInterestReceivable repurposed as Interest Expense → must be EXPENSE (5010)
     *     - glProvisionNpa repurposed as TDS Payable → must be LIABILITY (2500)
     *     - glInterestIncome repurposed as Interest Expense (duplicate) → must be EXPENSE
     *     - glProvisionExpense repurposed as Interest Expense → must be EXPENSE
     *     - glWriteOffExpense → EXPENSE (account closure charges)
     *     - glInterestSuspense must be LIABILITY
     *
     *   TERM_DEPOSIT (FD) categories:
     *     - Same as CASA — deposit liability + interest expense semantics
     *
     * Common to ALL categories:
     *     - glBankOperations must be ASSET (cash/teller GL 1100)
     *     - glFeeIncome must be INCOME (fee/charge income)
     *     - glPenalIncome must be INCOME (penalty charges)
     */
    private void validateGlCodes(String tid, ProductMaster p) {
        String cat = p.getProductCategory();
        boolean isCasa = "CASA_SAVINGS".equals(cat) || "CASA_CURRENT".equals(cat) || "TERM_DEPOSIT".equals(cat);

        if (isCasa) {
            // CASA/FD: Deposit-specific GL type validation
            validateGl(tid, p.getGlLoanAsset(), "Deposit Liability", GLAccountType.LIABILITY);
            validateGl(tid, p.getGlInterestReceivable(), "Interest Expense", GLAccountType.EXPENSE);
            validateGl(tid, p.getGlBankOperations(), "Bank Operations", GLAccountType.ASSET);
            validateGl(tid, p.getGlProvisionNpa(), "TDS Payable", GLAccountType.LIABILITY);
            validateGl(tid, p.getGlInterestIncome(), "Interest Expense (P&L)", GLAccountType.EXPENSE);
            validateGl(tid, p.getGlFeeIncome(), "Fee Income", GLAccountType.INCOME);
            validateGl(tid, p.getGlPenalIncome(), "Penalty Charges Income", GLAccountType.INCOME);
            validateGl(tid, p.getGlProvisionExpense(), "Interest Expense (Provision)", GLAccountType.EXPENSE);
            validateGl(tid, p.getGlWriteOffExpense(), "Closure/Write-Off Expense", GLAccountType.EXPENSE);
            validateGl(tid, p.getGlInterestSuspense(), "Interest Suspense", GLAccountType.LIABILITY);
        } else {
            // LOAN: Original loan-specific GL type validation
            validateGl(tid, p.getGlLoanAsset(), "Loan Asset", GLAccountType.ASSET);
            validateGl(tid, p.getGlInterestReceivable(), "Interest Receivable", GLAccountType.ASSET);
            validateGl(tid, p.getGlBankOperations(), "Bank Operations", GLAccountType.ASSET);
            validateGl(tid, p.getGlProvisionNpa(), "Provision NPA", GLAccountType.ASSET);
            validateGl(tid, p.getGlInterestIncome(), "Interest Income", GLAccountType.INCOME);
            validateGl(tid, p.getGlFeeIncome(), "Fee Income", GLAccountType.INCOME);
            validateGl(tid, p.getGlPenalIncome(), "Penal Income", GLAccountType.INCOME);
            validateGl(tid, p.getGlProvisionExpense(), "Provision Expense", GLAccountType.EXPENSE);
            validateGl(tid, p.getGlWriteOffExpense(), "Write-Off Expense", GLAccountType.EXPENSE);
            validateGl(tid, p.getGlInterestSuspense(), "Interest Suspense", GLAccountType.LIABILITY);
        }
    }

    private void validateGl(String tid, String glCode, String field, GLAccountType expected) {
        if (glCode == null || glCode.isBlank())
            throw new BusinessException("GL_REQUIRED", field + " GL code is mandatory.");
        GLMaster gl = glRepo.findByTenantIdAndGlCode(tid, glCode)
                .orElseThrow(() -> new BusinessException("GL_NOT_FOUND", field + " GL " + glCode + " not found."));
        if (!gl.isActive() || gl.isHeaderAccount())
            throw new BusinessException("GL_NOT_POSTABLE", field + " GL " + glCode + " is not postable.");
        if (gl.getAccountType() != expected)
            throw new BusinessException("GL_TYPE_MISMATCH",
                    field + " GL " + glCode + " is " + gl.getAccountType() + ", expected " + expected + ".");
    }

    private void validateStatusTransition(ProductStatus from, ProductStatus to, String code) {
        boolean valid = switch (from) {
            case DRAFT -> to == ProductStatus.ACTIVE;
            case ACTIVE -> to == ProductStatus.SUSPENDED || to == ProductStatus.RETIRED;
            case SUSPENDED -> to == ProductStatus.ACTIVE || to == ProductStatus.RETIRED;
            case RETIRED -> false;
        };
        if (!valid)
            throw new BusinessException("INVALID_TRANSITION",
                    "Product " + code + ": " + from + " -> " + to + " is not allowed.");
    }
}
