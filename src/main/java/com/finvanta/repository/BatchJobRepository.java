package com.finvanta.repository;

import com.finvanta.domain.entity.BatchJob;
import com.finvanta.domain.enums.BatchStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BatchJobRepository extends JpaRepository<BatchJob, Long> {

    List<BatchJob> findByTenantIdAndBusinessDate(String tenantId, LocalDate businessDate);

    Optional<BatchJob> findByTenantIdAndJobNameAndBusinessDate(String tenantId, String jobName, LocalDate businessDate);

    List<BatchJob> findByTenantIdAndStatus(String tenantId, BatchStatus status);

    List<BatchJob> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
