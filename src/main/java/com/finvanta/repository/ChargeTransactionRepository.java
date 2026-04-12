package com.finvanta.repository;

import com.finvanta.domain.entity.ChargeTransaction;
import com.finvanta.domain.enums.ChargeEventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Charge Transaction Repository per Finacle CHG_DETAIL.
 */
@Repository
public interface ChargeTransactionRepository
        extends JpaRepository<ChargeTransaction, Long> {

    /** Charges levied on an account for a date range (statement) */
    List<ChargeTransaction>
            findByTenantIdAndAccountNumberAndValueDateBetweenOrderByPostedAtAsc(
                    String tenantId, String accountNumber,
                    LocalDate fromDate, LocalDate toDate);

    /** Charges by source transaction (for clearing/loan linkage) */
    List<ChargeTransaction>
            findByTenantIdAndSourceModuleAndSourceRef(
                    String tenantId, String sourceModule,
                    String sourceRef);

    /** Total charges collected per event type for a date (MIS) */
    @Query("SELECT COALESCE(SUM(ct.baseFee), 0) "
            + "FROM ChargeTransaction ct "
            + "WHERE ct.tenantId = :tenantId "
            + "AND ct.eventType = :eventType "
            + "AND ct.valueDate = :valueDate "
            + "AND ct.waived = false")
    BigDecimal sumBaseFeeByEventAndDate(
            @Param("tenantId") String tenantId,
            @Param("eventType") ChargeEventType eventType,
            @Param("valueDate") LocalDate valueDate);

    /** Total GST collected for a date (GST return filing) */
    @Query("SELECT COALESCE(SUM(ct.cgstAmount + ct.sgstAmount), 0) "
            + "FROM ChargeTransaction ct "
            + "WHERE ct.tenantId = :tenantId "
            + "AND ct.valueDate = :valueDate "
            + "AND ct.waived = false")
    BigDecimal sumGstByDate(
            @Param("tenantId") String tenantId,
            @Param("valueDate") LocalDate valueDate);

    /** Waived charges for audit reporting */
    List<ChargeTransaction>
            findByTenantIdAndWaivedAndValueDateBetweenOrderByPostedAtAsc(
                    String tenantId, boolean waived,
                    LocalDate fromDate, LocalDate toDate);
}
