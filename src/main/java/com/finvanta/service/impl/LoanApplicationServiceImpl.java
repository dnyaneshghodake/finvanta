package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.rules.LoanEligibilityRule;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanApplicationRepository;
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
    private final LoanEligibilityRule eligibilityRule;
    private final ApprovalWorkflowService workflowService;
    private final AuditService auditService;

    public LoanApplicationServiceImpl(LoanApplicationRepository applicationRepository,
                                       CustomerRepository customerRepository,
                                       BranchRepository branchRepository,
                                       LoanEligibilityRule eligibilityRule,
                                       ApprovalWorkflowService workflowService,
                                       AuditService auditService) {
        this.applicationRepository = applicationRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.eligibilityRule = eligibilityRule;
        this.workflowService = workflowService;
        this.auditService = auditService;
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

        eligibilityRule.validate(application, customer);

        application.setTenantId(tenantId);
        application.setCustomer(customer);
        application.setBranch(branch);
        application.setApplicationNumber(ReferenceGenerator.generateApplicationNumber(branch.getBranchCode()));
        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setApplicationDate(LocalDate.now());
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

        if (app.getCreatedBy().equals(currentUser)) {
            throw new BusinessException("WORKFLOW_SELF_APPROVAL",
                "Maker cannot verify their own application");
        }

        ApplicationStatus previousStatus = app.getStatus();
        app.setStatus(ApplicationStatus.VERIFIED);
        app.setVerifiedBy(currentUser);
        app.setVerifiedDate(LocalDate.now());
        app.setRemarks(remarks);
        app.setUpdatedBy(currentUser);

        LoanApplication saved = applicationRepository.save(app);

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

        if (app.getCreatedBy().equals(currentUser)) {
            throw new BusinessException("WORKFLOW_SELF_APPROVAL",
                "Maker cannot approve their own application");
        }

        if (currentUser.equals(app.getVerifiedBy())) {
            throw new BusinessException("WORKFLOW_VERIFIER_APPROVER_SAME",
                "Verifier and approver should be different users");
        }

        Customer customer = app.getCustomer();
        eligibilityRule.validate(app, customer);

        ApplicationStatus previousStatus = app.getStatus();
        app.setStatus(ApplicationStatus.APPROVED);
        app.setApprovedAmount(app.getRequestedAmount());
        app.setApprovedBy(currentUser);
        app.setApprovedDate(LocalDate.now());
        app.setRemarks(remarks);
        app.setUpdatedBy(currentUser);

        LoanApplication saved = applicationRepository.save(app);

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
        app.setRejectedDate(LocalDate.now());
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
