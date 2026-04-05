package com.finvanta.repository;

import com.finvanta.domain.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByTenantIdAndBranchCode(String tenantId, String branchCode);

    List<Branch> findByTenantIdAndActiveTrue(String tenantId);

    boolean existsByTenantIdAndBranchCode(String tenantId, String branchCode);
}
