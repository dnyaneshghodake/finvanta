package com.finvanta.repository;

import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.ApplicationStatus;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    Optional<LoanApplication> findByTenantIdAndApplicationNumber(String tenantId, String applicationNumber);

    List<LoanApplication> findByTenantIdAndStatus(String tenantId, ApplicationStatus status);

    List<LoanApplication> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    boolean existsByTenantIdAndApplicationNumber(String tenantId, String applicationNumber);

    long countByTenantIdAndStatus(String tenantId, ApplicationStatus status);
}
