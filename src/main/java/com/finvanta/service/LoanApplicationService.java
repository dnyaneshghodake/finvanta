package com.finvanta.service;

import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.ApplicationStatus;

import java.util.List;

public interface LoanApplicationService {

    LoanApplication createApplication(LoanApplication application, Long customerId, Long branchId);

    LoanApplication verifyApplication(Long applicationId, String remarks);

    LoanApplication approveApplication(Long applicationId, String remarks);

    LoanApplication rejectApplication(Long applicationId, String reason);

    LoanApplication getApplication(Long applicationId);

    List<LoanApplication> getApplicationsByStatus(ApplicationStatus status);

    List<LoanApplication> getApplicationsByCustomer(Long customerId);
}
