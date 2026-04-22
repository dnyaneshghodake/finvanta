package com.finvanta.compliance;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Customer Certificate Generation Service per Finacle CERT_MASTER / Temenos EB.CERTIFICATE.
 *
 * <p>Per RBI Fair Practices Code 2023 and IT Act:
 * <ul>
 *   <li><b>Interest Certificate (Form 16A):</b> Mandatory per IT Act Section 203.
 *       Must be issued to depositors for TDS deducted on interest income.</li>
 *   <li><b>Form 15G/15H:</b> Per IT Act Section 197A — TDS exemption declaration
 *       for customers with income below taxable limit.</li>
 *   <li><b>Loan Closure NOC:</b> Per RBI Fair Practices Code — mandatory on loan closure.</li>
 *   <li><b>Balance Confirmation:</b> For auditors and regulatory purposes.</li>
 * </ul>
 *
 * <p><b>Phase 1:</b> Computes certificate data from existing repositories.
 * Phase 2: PDF generation using iText/PDFBox with bank letterhead templates.
 *
 * @see com.finvanta.domain.entity.DepositAccount#getYtdInterestCredited()
 */
@Service
public class CustomerCertificateService {

    private static final Logger log = LoggerFactory.getLogger(CustomerCertificateService.class);

    private final CustomerRepository customerRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final AuditService auditService;

    public CustomerCertificateService(
            CustomerRepository customerRepository,
            DepositAccountRepository depositAccountRepository,
            LoanAccountRepository loanAccountRepository,
            AuditService auditService) {
        this.customerRepository = customerRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.auditService = auditService;
    }

    /**
     * Generates Interest Certificate (Form 16A equivalent) data.
     *
     * <p>Per IT Act Section 203: Every deductor must issue a certificate
     * of TDS to the deductee within 15 days from the due date of filing
     * the quarterly TDS return.
     *
     * @param accountNumber Deposit account number
     * @param financialYear Financial year (e.g., "2024-25")
     * @return Map with certificate data fields
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generateInterestCertificateData(
            String accountNumber, String financialYear) {

        String tenantId = TenantContext.getCurrentTenant();

        DepositAccount account = depositAccountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "Account not found: " + accountNumber));

        Customer customer = account.getCustomer();

        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("certificateType", "INTEREST_CERTIFICATE_16A");
        cert.put("financialYear", financialYear);
        cert.put("accountNumber", accountNumber);
        cert.put("customerName", customer.getFullName());
        cert.put("customerNumber", customer.getCustomerNumber());
        cert.put("panNumber", customer.getMaskedPan());
        cert.put("branchCode", account.getBranch().getBranchCode());
        cert.put("branchName", account.getBranch().getBranchName());
        cert.put("accountType", account.getAccountType().name());

        // Interest and TDS data from YTD counters
        cert.put("grossInterest", account.getYtdInterestCredited());
        cert.put("tdsDeducted", account.getYtdTdsDeducted());
        cert.put("netInterest", account.getYtdInterestCredited()
                .subtract(account.getYtdTdsDeducted()));
        cert.put("tdsRate", "10%");
        cert.put("tdsSection", "194A");

        cert.put("generatedDate", LocalDate.now().toString());
        cert.put("generatedBy", SecurityUtil.getCurrentUsername());

        log.info("Interest certificate data generated: account={}, fy={}, interest={}, tds={}",
                accountNumber, financialYear,
                account.getYtdInterestCredited(), account.getYtdTdsDeducted());

        return cert;
    }

    /**
     * Generates Loan Closure NOC (No Objection Certificate) data.
     *
     * <p>Per RBI Fair Practices Code 2023: Banks must issue NOC within
     * 30 days of loan closure and release all original documents.
     *
     * @param accountNumber Loan account number
     * @return Map with NOC data fields
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generateLoanClosureNocData(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount loan = loanAccountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (!loan.getStatus().isTerminal()) {
            throw new BusinessException("LOAN_NOT_CLOSED",
                    "NOC can only be issued for closed/written-off loans. Current status: "
                            + loan.getStatus());
        }

        Customer customer = loan.getCustomer();

        Map<String, Object> noc = new LinkedHashMap<>();
        noc.put("certificateType", "LOAN_CLOSURE_NOC");
        noc.put("loanAccountNumber", accountNumber);
        noc.put("customerName", customer.getFullName());
        noc.put("customerNumber", customer.getCustomerNumber());
        noc.put("productType", loan.getProductType());
        noc.put("sanctionedAmount", loan.getSanctionedAmount());
        noc.put("disbursementDate", loan.getDisbursementDate());
        noc.put("closureDate", loan.getUpdatedAt() != null
                ? loan.getUpdatedAt().toLocalDate().toString() : null);
        noc.put("totalPrincipalRepaid", loan.getDisbursedAmount());
        noc.put("outstandingBalance", loan.getOutstandingPrincipal());
        noc.put("collateralReference", loan.getCollateralReference());
        noc.put("branchCode", loan.getBranch().getBranchCode());
        noc.put("branchName", loan.getBranch().getBranchName());

        noc.put("declaration",
                "This is to certify that the above loan account has been closed "
                        + "and all dues have been settled. The bank has no further claim "
                        + "on the borrower in respect of this loan facility. "
                        + "All original documents/securities will be released within 30 days.");

        noc.put("generatedDate", LocalDate.now().toString());
        noc.put("generatedBy", SecurityUtil.getCurrentUsername());

        log.info("Loan closure NOC data generated: loan={}, customer={}",
                accountNumber, customer.getCustomerNumber());

        auditService.logEvent(
                "LoanAccount",
                loan.getId(),
                "NOC_GENERATED",
                null,
                accountNumber,
                "CERTIFICATE",
                "Loan closure NOC generated for " + accountNumber);

        return noc;
    }

    /**
     * Generates Balance Confirmation Certificate data.
     *
     * <p>Used for statutory audits and customer requests.
     *
     * @param accountNumber Deposit account number
     * @param asOfDate Date for which balance is confirmed
     * @return Map with balance confirmation data
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generateBalanceConfirmationData(
            String accountNumber, LocalDate asOfDate) {

        String tenantId = TenantContext.getCurrentTenant();

        DepositAccount account = depositAccountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "Account not found: " + accountNumber));

        Customer customer = account.getCustomer();

        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("certificateType", "BALANCE_CONFIRMATION");
        confirmation.put("accountNumber", accountNumber);
        confirmation.put("customerName", customer.getFullName());
        confirmation.put("asOfDate", asOfDate.toString());
        confirmation.put("ledgerBalance", account.getLedgerBalance());
        confirmation.put("availableBalance", account.getAvailableBalance());
        confirmation.put("holdAmount", account.getHoldAmount());
        confirmation.put("accountStatus", account.getAccountStatus().name());
        confirmation.put("branchCode", account.getBranch().getBranchCode());
        confirmation.put("generatedDate", LocalDate.now().toString());
        confirmation.put("generatedBy", SecurityUtil.getCurrentUsername());

        return confirmation;
    }
}
