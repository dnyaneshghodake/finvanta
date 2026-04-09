package com.finvanta.accounting;

import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.util.TenantContext;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ProductGLResolver Tests per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * Validates product-aware GL code resolution with fallback to GLConstants.
 */
@ExtendWith(MockitoExtension.class)
class ProductGLResolverTest {

    @Mock
    private ProductMasterRepository productRepository;

    @InjectMocks
    private ProductGLResolver glResolver;

    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getCurrentTenant).thenReturn("DEFAULT");
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private ProductMaster createProduct(String code) {
        ProductMaster product = new ProductMaster();
        product.setProductCode(code);
        product.setGlLoanAsset("1010"); // Different from GLConstants.LOAN_ASSET ("1001")
        product.setGlInterestReceivable("1020");
        product.setGlBankOperations("1200");
        product.setGlInterestIncome("4010");
        product.setGlFeeIncome("4020");
        product.setGlPenalIncome("4030");
        product.setGlProvisionExpense("5010");
        product.setGlProvisionNpa("1030");
        product.setGlWriteOffExpense("5020");
        product.setGlInterestSuspense("2200");
        return product;
    }

    @Test
    @DisplayName("Product configured → returns product-specific GL code")
    void productConfigured_returnsProductGL() {
        ProductMaster product = createProduct("HOME_LOAN");
        when(productRepository.findByTenantIdAndProductCode("DEFAULT", "HOME_LOAN"))
                .thenReturn(Optional.of(product));

        assertEquals("1010", glResolver.getLoanAssetGL("HOME_LOAN"));
        assertEquals("1020", glResolver.getInterestReceivableGL("HOME_LOAN"));
        assertEquals("1200", glResolver.getBankOperationsGL("HOME_LOAN"));
        assertEquals("4010", glResolver.getInterestIncomeGL("HOME_LOAN"));
        assertEquals("4020", glResolver.getFeeIncomeGL("HOME_LOAN"));
        assertEquals("4030", glResolver.getPenalIncomeGL("HOME_LOAN"));
        assertEquals("5010", glResolver.getProvisionExpenseGL("HOME_LOAN"));
        assertEquals("1030", glResolver.getProvisionNpaGL("HOME_LOAN"));
        assertEquals("5020", glResolver.getWriteOffExpenseGL("HOME_LOAN"));
        assertEquals("2200", glResolver.getInterestSuspenseGL("HOME_LOAN"));
    }

    @Test
    @DisplayName("Product not configured → falls back to GLConstants")
    void productNotConfigured_fallsBackToConstants() {
        when(productRepository.findByTenantIdAndProductCode("DEFAULT", "UNKNOWN_PRODUCT"))
                .thenReturn(Optional.empty());

        assertEquals(GLConstants.LOAN_ASSET, glResolver.getLoanAssetGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.INTEREST_RECEIVABLE, glResolver.getInterestReceivableGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.BANK_OPERATIONS, glResolver.getBankOperationsGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.INTEREST_INCOME, glResolver.getInterestIncomeGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.FEE_INCOME, glResolver.getFeeIncomeGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.PENAL_INTEREST_INCOME, glResolver.getPenalIncomeGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.PROVISION_EXPENSE, glResolver.getProvisionExpenseGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.PROVISION_NPA, glResolver.getProvisionNpaGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.WRITE_OFF_EXPENSE, glResolver.getWriteOffExpenseGL("UNKNOWN_PRODUCT"));
        assertEquals(GLConstants.INTEREST_SUSPENSE, glResolver.getInterestSuspenseGL("UNKNOWN_PRODUCT"));
    }

    @Test
    @DisplayName("Null product type → falls back to GLConstants")
    void nullProductType_fallsBackToConstants() {
        assertEquals(GLConstants.LOAN_ASSET, glResolver.getLoanAssetGL(null));
        assertEquals(GLConstants.BANK_OPERATIONS, glResolver.getBankOperationsGL(null));
    }

    @Test
    @DisplayName("getProduct returns ProductMaster when configured")
    void getProduct_returnsProduct() {
        ProductMaster product = createProduct("GOLD_LOAN");
        when(productRepository.findByTenantIdAndProductCode("DEFAULT", "GOLD_LOAN"))
                .thenReturn(Optional.of(product));

        ProductMaster result = glResolver.getProduct("GOLD_LOAN");
        assertNotNull(result);
        assertEquals("GOLD_LOAN", result.getProductCode());
    }

    @Test
    @DisplayName("getProduct returns null when not configured")
    void getProduct_returnsNull() {
        when(productRepository.findByTenantIdAndProductCode("DEFAULT", "UNKNOWN"))
                .thenReturn(Optional.empty());

        assertNull(glResolver.getProduct("UNKNOWN"));
    }

    @Test
    @DisplayName("getProduct with null product type returns null")
    void getProduct_nullType_returnsNull() {
        assertNull(glResolver.getProduct(null));
    }
}
