package com.finvanta.repository;

import com.finvanta.domain.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByTenantIdAndCustomerNumber(String tenantId, String customerNumber);

    List<Customer> findByTenantIdAndActiveTrue(String tenantId);

    Optional<Customer> findByTenantIdAndPanNumber(String tenantId, String panNumber);

    boolean existsByTenantIdAndCustomerNumber(String tenantId, String customerNumber);

    List<Customer> findByTenantIdAndKycVerifiedFalse(String tenantId);
}
