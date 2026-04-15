package com.finvanta.controller;

import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.DepositAccountType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.StandingInstructionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CBS Controller-Level Tests for CASA Search and CSV Export.
 * Per Finacle ACCTINQ / RBI IT Governance: validates HTTP-level behavior
 * including response codes, model attributes, and CSV output format.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("DepositController — Search & Export")
class DepositControllerSearchTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private DepositAccountService depositService;
    @MockBean private BusinessDateService businessDateService;
    @MockBean private CustomerRepository customerRepository;
    @MockBean private BranchRepository branchRepository;
    @MockBean private DepositAccountRepository depositAccountRepository;
    @MockBean private StandingInstructionRepository siRepository;
    @MockBean private ProductMasterRepository productMasterRepository;
    @MockBean private BranchAccessValidator branchAccessValidator;

    private DepositAccount testAccount;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("DEFAULT");
        Branch branch = new Branch();
        branch.setId(1L);
        branch.setBranchCode("HQ001");
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setCustomerNumber("CUST001");
        customer.setFirstName("Rajesh");
        customer.setLastName("Sharma");

        testAccount = new DepositAccount();
        testAccount.setId(1L);
        testAccount.setTenantId("DEFAULT");
        testAccount.setAccountNumber("SB-HQ001-000001");
        testAccount.setAccountType(DepositAccountType.SAVINGS);
        testAccount.setAccountStatus(DepositAccountStatus.ACTIVE);
        testAccount.setLedgerBalance(new BigDecimal("50000.00"));
        testAccount.setBranch(branch);
        testAccount.setCustomer(customer);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Search with valid query returns results")
    void search_validQuery_returnsResults() throws Exception {
        when(depositAccountRepository.searchAccounts(eq("DEFAULT"), eq("Rajesh")))
                .thenReturn(List.of(testAccount));

        mockMvc.perform(get("/deposit/search").param("q", "Rajesh"))
                .andExpect(status().isOk())
                .andExpect(view().name("deposit/accounts"))
                .andExpect(model().attributeExists("accounts"))
                .andExpect(model().attribute("searchQuery", "Rajesh"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Search with short query redirects to list")
    void search_shortQuery_redirectsToList() throws Exception {
        mockMvc.perform(get("/deposit/search").param("q", "a"))
                .andExpect(status().isOk())
                .andExpect(view().name("deposit/accounts"));
        // Should NOT call search — falls back to listAccounts
        verify(depositAccountRepository, never()).searchAccounts(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CSV export returns valid CSV with headers")
    void exportStatement_returnsCsv() throws Exception {
        DepositTransaction txn = new DepositTransaction();
        txn.setValueDate(LocalDate.of(2026, 4, 1));
        txn.setTransactionRef("TXN001");
        txn.setTransactionType("CASH_DEPOSIT");
        txn.setChannel("BRANCH");
        txn.setNarration("Cash deposit");
        txn.setDebitCredit("CREDIT");
        txn.setAmount(new BigDecimal("10000.00"));
        txn.setBalanceAfter(new BigDecimal("60000.00"));
        txn.setVoucherNumber("VCH/HQ001/20260401/000001");

        when(depositService.getAccount("SB-HQ001-000001")).thenReturn(testAccount);
        when(businessDateService.getCurrentBusinessDate()).thenReturn(LocalDate.of(2026, 4, 1));
        when(depositService.getStatement(eq("SB-HQ001-000001"), any(), any()))
                .thenReturn(List.of(txn));

        mockMvc.perform(get("/deposit/statement/SB-HQ001-000001/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", containsString("Statement_SB-HQ001-000001")))
                .andExpect(content().string(containsString("Date,Transaction Ref,Type")))
                .andExpect(content().string(containsString("TXN001")))
                .andExpect(content().string(containsString("10000.00")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CSV export handles malformed dates gracefully")
    void exportStatement_malformedDate_fallsBack() throws Exception {
        when(depositService.getAccount("SB-HQ001-000001")).thenReturn(testAccount);
        when(businessDateService.getCurrentBusinessDate()).thenReturn(LocalDate.of(2026, 4, 1));
        when(depositService.getStatement(eq("SB-HQ001-000001"), any(), any()))
                .thenReturn(List.of());

        // Malformed date should not cause 500 — falls back to default range
        mockMvc.perform(get("/deposit/statement/SB-HQ001-000001/export")
                        .param("fromDate", "not-a-date")
                        .param("toDate", "also-bad"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"));
    }
}
