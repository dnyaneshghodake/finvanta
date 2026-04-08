package com.finvanta.repository;

import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.enums.GLAccountType;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GLMasterRepository extends JpaRepository<GLMaster, Long> {

    Optional<GLMaster> findByTenantIdAndGlCode(String tenantId, String glCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.glCode = :glCode")
    Optional<GLMaster> findAndLockByTenantIdAndGlCode(
            @Param("tenantId") String tenantId, @Param("glCode") String glCode);

    List<GLMaster> findByTenantIdAndActiveTrue(String tenantId);

    List<GLMaster> findByTenantIdAndAccountType(String tenantId, GLAccountType accountType);

    @Query(
            "SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.headerAccount = false AND gl.active = true ORDER BY gl.glCode")
    List<GLMaster> findAllPostableAccounts(@Param("tenantId") String tenantId);

    /** All GL accounts (including headers) ordered by code for chart of accounts display */
    @Query("SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.active = true ORDER BY gl.glCode")
    List<GLMaster> findAllActiveOrderByCode(@Param("tenantId") String tenantId);

    /** Child GL accounts under a parent header (for GL hierarchy traversal) */
    @Query(
            "SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.parentGlCode = :parentCode AND gl.active = true ORDER BY gl.glCode")
    List<GLMaster> findChildAccounts(@Param("tenantId") String tenantId, @Param("parentCode") String parentCode);

    /** All GL accounts of a specific type (for financial statement sections) */
    @Query(
            "SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.accountType = :type AND gl.headerAccount = false AND gl.active = true ORDER BY gl.glCode")
    List<GLMaster> findPostableByType(@Param("tenantId") String tenantId, @Param("type") GLAccountType type);
}
