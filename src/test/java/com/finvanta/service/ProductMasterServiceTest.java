package com.finvanta.service;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.domain.enums.ProductCategory;
import com.finvanta.domain.enums.ProductStatus;
import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.domain.enums.ApprovalStatus;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.ApprovalWorkflowRepository;
import com.finvanta.service.impl.ProductMasterServiceImpl;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CBS Product Master Service Tests per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * Validates category-aware GL validation introduced in PR #22:
 * - LOAN products: ASSET GLs for principal, INCOME for interest
 * - CASA products: LIABILITY GLs for deposits, EXPENSE for interest
 * - FD products: LIABILITY GLs for deposits + interest payable
 * - Category immutability on edit
 * - Product code format validation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductMasterService — Category-Aware GL Validation")
class ProductMasterServiceTest {

    @Mock private ProductMasterRepository productRepo;
    @Mock private GLMasterRepository glRepo;
    @Mock private LoanAccountRepository loanAccountRepo;
    @Mock private DepositAccountRepository depositAccountRepo;
    @Mock private ProductGLResolver glResolver;
    @Mock private AuditService auditSvc;
    @Mock private ApprovalWorkflowService workflowService;
    @Mock private ApprovalWorkflowRepository workflowRepo;

    private ProductMasterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductMasterServiceImpl(
                productRepo, glRepo, loanAccountRepo, depositAccountRepo,
                glResolver, auditSvc, workflowService, workflowRepo);
        TenantContext.setCurrentTenant("DEFAULT");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin", "pass",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void mockGl(String code, GLAccountType type) {
        GLMaster gl = new GLMaster();
        gl.setGlCode(code);
        gl.setAccountType(type);
        gl.setActive(true);
        gl.setHeaderAccount(false);
        lenient().when(glRepo.findByTenantIdAndGlCode("DEFAULT", code))
                .thenReturn(Optional.of(gl));
    }

    private ProductMaster buildLoanProduct() {
        ProductMaster p = new ProductMaster();
        p.setProductCode("HOME_LOAN");
        p.setProductName("Home Loan");
        p.setProductCategory(ProductCategory.TERM_LOAN);
        p.setMinInterestRate(new BigDecimal("8.00"));
        p.setMaxInterestRate(new BigDecimal("14.00"));
        p.setGlLoanAsset("1001");
        p.setGlInterestReceivable("1002");
        p.setGlBankOperations("1100");
        p.setGlInterestIncome("4001");
        p.setGlFeeIncome("4002");
        p.setGlPenalIncome("4003");
        p.setGlProvisionExpense("5001");
        p.setGlProvisionNpa("1003");
        p.setGlWriteOffExpense("5002");
        p.setGlInterestSuspense("2100");
        return p;
    }

    private ProductMaster buildCasaProduct() {
        ProductMaster p = new ProductMaster();
        p.setProductCode("SAVINGS");
        p.setProductName("Savings Account");
        p.setProductCategory(ProductCategory.CASA_SAVINGS);
        p.setMinInterestRate(new BigDecimal("4.00"));
        p.setMaxInterestRate(new BigDecimal("4.00"));
        p.setGlLoanAsset("2010");
        p.setGlInterestReceivable("5010");
        p.setGlBankOperations("1100");
        p.setGlInterestIncome("5010");
        p.setGlFeeIncome("4002");
        p.setGlPenalIncome("4003");
        p.setGlProvisionExpense("5010");
        p.setGlProvisionNpa("2500");
        p.setGlWriteOffExpense("5002");
        p.setGlInterestSuspense("2100");
        return p;
    }

    private void mockLoanGLs() {
        mockGl("1001", GLAccountType.ASSET);
        mockGl("1002", GLAccountType.ASSET);
        mockGl("1003", GLAccountType.ASSET);
        mockGl("1100", GLAccountType.ASSET);
        mockGl("4001", GLAccountType.INCOME);
        mockGl("4002", GLAccountType.INCOME);
        mockGl("4003", GLAccountType.INCOME);
        mockGl("5001", GLAccountType.EXPENSE);
        mockGl("5002", GLAccountType.EXPENSE);
        mockGl("2100", GLAccountType.LIABILITY);
    }

    private void mockCasaGLs() {
        mockGl("2010", GLAccountType.LIABILITY);
        mockGl("2500", GLAccountType.LIABILITY);
        mockGl("5010", GLAccountType.EXPENSE);
        mockGl("1100", GLAccountType.ASSET);
        mockGl("4002", GLAccountType.INCOME);
        mockGl("4003", GLAccountType.INCOME);
        mockGl("5002", GLAccountType.EXPENSE);
        mockGl("2100", GLAccountType.LIABILITY);
    }

    @Nested
    @DisplayName("Create Product — GL Validation")
    class CreateProductGLValidation {

        @Test
        @DisplayName("LOAN product with ASSET GLs should succeed")
        void loanProduct_assetGLs_succeeds() {
            mockLoanGLs();
            ProductMaster p = buildLoanProduct();
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "HOME_LOAN"))
                    .thenReturn(Optional.empty());
            when(productRepo.save(any())).thenAnswer(inv -> {
                ProductMaster saved = inv.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            ProductMaster result = service.createProduct(p);
            assertNotNull(result);
            assertEquals("HOME_LOAN", result.getProductCode());
            verify(productRepo).save(any());
        }

        @Test
        @DisplayName("CASA product with LIABILITY/EXPENSE GLs should succeed")
        void casaProduct_liabilityGLs_succeeds() {
            mockCasaGLs();
            ProductMaster p = buildCasaProduct();
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "SAVINGS"))
                    .thenReturn(Optional.empty());
            when(productRepo.save(any())).thenAnswer(inv -> {
                ProductMaster saved = inv.getArgument(0);
                saved.setId(2L);
                return saved;
            });

            ProductMaster result = service.createProduct(p);
            assertNotNull(result);
            assertEquals(ProductCategory.CASA_SAVINGS, result.getProductCategory());
        }

        @Test
        @DisplayName("CASA product with ASSET GL for deposit liability should FAIL")
        void casaProduct_assetGLForDeposit_fails() {
            mockGl("1001", GLAccountType.ASSET);
            mockCasaGLs();
            ProductMaster p = buildCasaProduct();
            p.setGlLoanAsset("1001");
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "SAVINGS"))
                    .thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("GL_TYPE_MISMATCH", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("Deposit Liability"));
        }

        @Test
        @DisplayName("LOAN product with LIABILITY GL for loan asset should FAIL")
        void loanProduct_liabilityGLForAsset_fails() {
            mockLoanGLs();
            mockGl("2010", GLAccountType.LIABILITY);
            ProductMaster p = buildLoanProduct();
            p.setGlLoanAsset("2010");
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "HOME_LOAN"))
                    .thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("GL_TYPE_MISMATCH", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("Loan Asset"));
        }

        @Test
        @DisplayName("Null product category should be rejected")
        void nullCategory_rejected() {
            ProductMaster p = buildLoanProduct();
            p.setProductCategory(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("CATEGORY_REQUIRED", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Update Product — Category Immutability")
    class UpdateProductCategoryImmutability {

        @Test
        @DisplayName("Edit preserves category from existing entity (null from form)")
        void editPreservesCategory() {
            mockCasaGLs();
            ProductMaster existing = buildCasaProduct();
            existing.setId(10L);
            existing.setTenantId("DEFAULT");
            existing.setProductStatus(ProductStatus.ACTIVE);
            existing.setConfigVersion(1);

            ProductMaster formData = buildCasaProduct();
            formData.setProductCategory(null);

            when(productRepo.findById(10L)).thenReturn(Optional.of(existing));
            when(productRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ProductMaster result = service.updateProduct(10L, formData);
            assertEquals(ProductCategory.CASA_SAVINGS, result.getProductCategory());
        }
    }

    @Nested
    @DisplayName("GL Change Workflow Consumption")
    class GlChangeWorkflowConsumption {

        @Test
        @DisplayName("Approved GL workflow is consumed (APPROVED→CONSUMED) after application")
        void approvedWorkflow_consumedAfterApplication() {
            mockCasaGLs();
            ProductMaster existing = buildCasaProduct();
            existing.setId(10L);
            existing.setTenantId("DEFAULT");
            existing.setProductStatus(ProductStatus.ACTIVE);
            existing.setConfigVersion(1);
            // Change one GL code to trigger GL change detection
            existing.setGlLoanAsset("2010");

            ProductMaster formData = buildCasaProduct();
            formData.setProductCategory(null);
            formData.setGlLoanAsset("2020"); // Different from existing → GL change detected

            // Mock active accounts exist → maker-checker required
            when(productRepo.findById(10L)).thenReturn(java.util.Optional.of(existing));
            when(loanAccountRepo.countActiveByProductType(any(), any())).thenReturn(0L);
            when(depositAccountRepo.countNonClosedByProductCode(any(), any())).thenReturn(5L);

            // Mock APPROVED workflow exists
            mockGl("2020", GLAccountType.LIABILITY);
            ApprovalWorkflow approvedWf = new ApprovalWorkflow();
            approvedWf.setId(99L);
            approvedWf.setTenantId("DEFAULT");
            approvedWf.setEntityType("ProductMaster");
            approvedWf.setEntityId(10L);
            approvedWf.setActionType("PRODUCT_GL_CHANGE");
            approvedWf.setStatus(ApprovalStatus.APPROVED);
            approvedWf.setCheckerUserId("checker1");
            when(workflowRepo.findByTenantIdAndEntityTypeAndEntityIdAndStatus(
                    "DEFAULT", "ProductMaster", 10L, ApprovalStatus.APPROVED))
                    .thenReturn(java.util.Optional.of(approvedWf));
            when(productRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.updateProduct(10L, formData);

            // Verify workflow was consumed (status set to CONSUMED)
            assertEquals(ApprovalStatus.CONSUMED, approvedWf.getStatus());
            verify(workflowRepo).save(approvedWf);
        }
    }

    @Nested
    @DisplayName("Product Code Validation")
    class ProductCodeValidation {

        @Test
        @DisplayName("Lowercase product code rejected")
        void lowercaseCode_rejected() {
            ProductMaster p = buildLoanProduct();
            p.setProductCode("home_loan");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("INVALID_PRODUCT_CODE", ex.getErrorCode());
        }

        @Test
        @DisplayName("Duplicate product code rejected")
        void duplicateCode_rejected() {
            ProductMaster p = buildLoanProduct();
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "HOME_LOAN"))
                    .thenReturn(Optional.of(new ProductMaster()));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("DUPLICATE_PRODUCT", ex.getErrorCode());
        }

        @Test
        @DisplayName("Min rate > max rate rejected")
        void minRateExceedsMax_rejected() {
            mockLoanGLs();
            ProductMaster p = buildLoanProduct();
            p.setMinInterestRate(new BigDecimal("20.00"));
            p.setMaxInterestRate(new BigDecimal("10.00"));
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "HOME_LOAN"))
                    .thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("INVALID_RATE_RANGE", ex.getErrorCode());
        }

        @Test
        @DisplayName("Penal rate exceeding RBI 36% usury ceiling rejected")
        void penalRateExceedsCeiling_rejected() {
            mockLoanGLs();
            ProductMaster p = buildLoanProduct();
            p.setDefaultPenalRate(new BigDecimal("40.00"));
            when(productRepo.findByTenantIdAndProductCode("DEFAULT", "HOME_LOAN"))
                    .thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createProduct(p));
            assertEquals("INVALID_PENAL_RATE", ex.getErrorCode());
        }
    }
}
