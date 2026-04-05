package com.finvanta.repository;

import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.enums.GLAccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GLMasterRepository extends JpaRepository<GLMaster, Long> {

    Optional<GLMaster> findByTenantIdAndGlCode(String tenantId, String glCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.glCode = :glCode")
    Optional<GLMaster> findAndLockByTenantIdAndGlCode(
        @Param("tenantId") String tenantId,
        @Param("glCode") String glCode
    );

    List<GLMaster> findByTenantIdAndActiveTrue(String tenantId);

    List<GLMaster> findByTenantIdAndAccountType(String tenantId, GLAccountType accountType);

    @Query("SELECT gl FROM GLMaster gl WHERE gl.tenantId = :tenantId AND gl.headerAccount = false AND gl.active = true ORDER BY gl.glCode")
    List<GLMaster> findAllPostableAccounts(@Param("tenantId") String tenantId);
}
