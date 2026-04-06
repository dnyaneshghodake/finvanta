package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.rules.LoanEligibilityRule;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.LoanApplicationService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationServiceImpl.class);

    private final LoanApplicationRepository applicationRepository;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final ProductMasterRepository productRepository;
    private final LoanEligibilityRule eligibilityRule;
    private final ApprovalWorkflowService workflowService;
    private final AuditService auditService;
    private final BusinessDateService businessDateService;

    public LoanApplicationServiceImpl(LoanApplicationRepository applicationRepository,
                                       CustomerRepository customerRepository,
                                       BranchRepository branchRepository,
                                       ProductMasterRepository productRepository,
                                       LoanEligibilityRule eligibilityRule,
                                       ApprovalWorkflowService workflowService,
                                       AuditService auditService,
                                       BusinessDateService businessDateService) {
        this.applicationRepository = applicationRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.productRepository = productRepository;
        this.eligibilityRule = eligibilityRule;
        this.workflowService = workflowService;
        this.auditService = auditService;
        this.businessDateService = businessDateService;
    }

    @Override
    @Transactional
    public LoanApplication createApplication(LoanApplication application, Long customerId, Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        Customer customer = customerRepository.findById(customerId)
            .filter(c -> c.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND",
                "Customer not found: " + customerId));

        Branch branch = branchRepository.findById(branchId)
            .filter(b -> b.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND",
                "Branch not found: " + branchId));

        // CBS: Resolve product for product-driven validation (amount/tenure/rate limits)
        // Per Finacle PDDEF, each product defines its own eligibility bounds.
        ProductMaster product = (application.getProductType() != null)
            ? productRepository.findByTenantIdAndProductCode(tenantId, application.getProductType())
                .orElse(null)
            : null;
        eligibilityRule.validate(application, customer, product);

        application.setTenantId(tenantId);
        application.setCustomer(customer);
        application.setBranch(branch);
        application.setApplicationNumber(ReferenceGenerator.generateApplicationNumber(branch.getBranchCode()));
        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setApplicationDate(businessDateService.getCurrentBusinessDate());
        application.setCreatedBy(currentUser);

        LoanApplication saved = applicationRepository.save(application);

        workflowService.initiateApproval(
            "LoanApplication", saved.getId(),
            "VERIFY", "New loan application submitted",
            saved.getApplicationNumber()
        );

        auditService.logEvent("LoanApplication", saved.getId(), "CREATE",
            null, saved, "LOAN_ORIGINATION",
            "Loan application created: " + saved.getApplicationNumber());

        log.info("Loan application created: appNo={}, customer={}, amount={}",
            saved.getApplicationNumber(), customer.getCustomerNumber(), application.getRequestedAmount());

        return saved;
    }

    @Override
    @Transactional
    public LoanApplication verifyApplication(Long applicationId, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanApplication app = applicationRepository.findById(applicationId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND",
                "Loan application not found: " + applicationId));

        if (app.getStatus() != ApplicationStatus.SUBMITTED
                && app.getStatus() != ApplicationStatus.UNDER_VERIFICATION) {
            throw new BusinessException("APPLICATION_INVALID_STATE",
                "Application cannot be verified in current state: " + app.getStatus());
        }

        if (currentUser.equals(app.getCreatedBy())) {
            throw new BusinessException("WORKFLOW_SELF_APPROVAL",
                "Maker cannot verify their own application");
        }

        ApplicationStatus previousStatus = app.getStatus();
        app.setStatus(ApplicationStatus.VERIFIED);
        app.setVerifiedBy(currentUser);
        app.setVerifiedDate(businessDateService.getCurrentBusinessDate());
        app.setRemarks(remarks);
        app.setUpdatedBy(currentUser);

        LoanApplication saved = applicationRepository.save(app);

        // Resolve the existing VERIFY workflow before initiating APPROVE
        workflowService.resolveExistingPendingWorkflow(
            "LoanApplication", saved.getId(), currentUser, "Verified");

        workflowService.initiateApproval(
            "LoanApplication", saved.getId(),
            "APPROVE", "Verified, pending approval",
            saved.getApplicationNumber()
        );

        auditService.logEvent("LoanApplication", saved.getId(), "VERIFY",
            previousStatus.name(), saved.getStatus().name(), "LOAN_ORIGINATION",
            "Application verified by " + currentUser);

        log.info("Application verified: appNo={}, verifier={}", app.getApplicationNumber(), currentUser);

        return saved;
    }

    @Override
    @Transactional
    public LoanApplication approveApplication(Long applicationId, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanApplication app = applicationRepository.findById(applicationId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND",
                "Loan application not found: " + applicationId));

        if (app.getStatus() != ApplicationStatus.VERIFIED
                && app.getStatus() != ApplicationStatus.PENDING_APPROVAL) {
            throw new BusinessException("APPLICATION_INVALID_STATE",
                "Application cannot be approved in current state: " + app.getStatus());
        }

        if (currentUser.equals(app.getCreatedBy())) {
            throw new BusinessException("WORKFLOW_SELF_APPROVAL",
                "Maker cannot approve their own application");
        }

        if (currentUser.equals(app.getVerifiedBy())) {
            throw new BusinessException("WORKFLOW_VERIFIER_APPROVER_SAME",
                "Verifier and approver should be different users");
        }

        // CBS: Re-validate at approval with product-driven limits (defense-in-depth)
        Customer customer = app.getCustomer();
        ProductMaster product = (app.getProductType() != null)
            ? productRepository.findByTenantIdAndProductCode(tenantId, app.getProductType())
                .orElse(null)
            : null;
        eligibilityRule.validate(app, customer, product);

        ApplicationStatus previousStatus = app.getStatus();
        app.setStatus(ApplicationStatus.APPROVED);
        app.setApprovedAmount(app.getRequestedAmount());
        app.setApprovedBy(currentUser);
        app.setApprovedDate(businessDateService.getCurrentBusinessDate());
        app.setRemarks(remarks);
        app.setUpdatedBy(currentUser);

        LoanApplication saved = applicationRepository.save(app);

        // Resolve the APPROVE workflow — verifyApplication initiated it, approval completes it.
        // Without this, the workflow stays in PENDING_APPROVAL forever.
        workflowService.resolveExistingPendingWorkflow(
            "LoanApplication", saved.getId(), currentUser, "Approved: " + remarks);

        auditService.logEvent("LoanApplication", saved.getId(), "APPROVE",
            previousStatus.name(), saved.getStatus().name(), "LOAN_ORIGINATION",
            "Application approved by " + currentUser + ", amount: " + saved.getApprovedAmount());

        log.info("Application approved: appNo={}, approver={}, amount={}",
            app.getApplicationNumber(), currentUser, app.getApprovedAmount());

        return saved;
    }

    @Override
    @Transactional
    public LoanApplication rejectApplication(Long applicationId, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // RBI Fair Practices Code 2023: Rejection reason is mandatory and must be
        // communicated to the borrower. Empty reasons violate regulatory requirements.
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REJECTION_REASON_REQUIRED",
                "Rejection reason is mandatory per RBI Fair Practices Code. "
                    + "Banks must communicate specific reasons for loan rejection.");
        }

        LoanApplication app = applicationRepository.findById(applicationId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND",
                "Loan application not found: " + applicationId));

        if (app.getStatus() == ApplicationStatus.DISBURSED
                || app.getStatus() == ApplicationStatus.CANCELLED) {
            throw new BusinessException("APPLICATION_INVALID_STATE",
                "Application cannot be rejected in current state: " + app.getStatus());
        }

        ApplicationStatus previousStatus = app.getStatus();
        app.setStatus(ApplicationStatus.REJECTED);
        app.setRejectedBy(currentUser);
        app.setRejectedDate(businessDateService.getCurrentBusinessDate());
        app.setRejectionReason(reason);
        app.setUpdatedBy(currentUser);

        LoanApplication saved = applicationRepository.save(app);

        auditService.logEvent("LoanApplication", saved.getId(), "REJECT",
            previousStatus.name(), saved.getStatus().name(), "LOAN_ORIGINATION",
            "Application rejected by " + currentUser + ": " + reason);

        log.info("Application rejected: appNo={}, rejector={}", app.getApplicationNumber(), currentUser);

        return saved;
    }

    @Override
    public LoanApplication getApplication(Long applicationId) {
        String tenantId = TenantContext.getCurrentTenant();
        return applicationRepository.findById(applicationId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND",
                "Loan application not found: " + applicationId));
    }

    @Override
    public List<LoanApplication> getApplicationsByStatus(ApplicationStatus status) {
        return applicationRepository.findByTenantIdAndStatus(TenantContext.getCurrentTenant(), status);
    }

    @Override
    public List<LoanApplication> getApplicationsByCustomer(Long customerId) {
        return applicationRepository.findByTenantIdAndCustomerId(TenantContext.getCurrentTenant(), customerId);
    }
}
