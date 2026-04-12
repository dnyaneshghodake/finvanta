package com.finvanta.api;

import com.finvanta.charge.ChargeEngine;
import com.finvanta.charge.ChargeResult;
import com.finvanta.domain.entity.ChargeTransaction;
import com.finvanta.domain.enums.ChargeEventType;
import com.finvanta.repository.ChargeTransactionRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Charge/Fee REST API per Finacle CHG_API / Temenos FT.COMMISSION.
 *
 * Thin orchestration layer over ChargeEngine.
 * Per Finacle API standards: request DTOs, response DTOs, role-based access.
 *
 * CBS Role Matrix for Charges:
 *   MAKER   → levy charge (via clearing/loan module — not direct)
 *   CHECKER → waive charge
 *   ADMIN   → all operations + charge definition management
 */
@RestController
@RequestMapping("/api/v1/charges")
public class ChargeController {

    private final ChargeEngine chargeEngine;
    private final ChargeTransactionRepository chargeTxnRepo;

    public ChargeController(
            ChargeEngine chargeEngine,
            ChargeTransactionRepository chargeTxnRepo) {
        this.chargeEngine = chargeEngine;
        this.chargeTxnRepo = chargeTxnRepo;
    }

    /** Levy a charge on a customer account. */
    @PostMapping("/levy")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ChargeResponse>>
            levyCharge(@RequestBody LevyRequest req) {
        ChargeResult result = chargeEngine.levyCharge(
                ChargeEventType.valueOf(req.eventType()),
                req.accountNumber(),
                req.customerGlCode(),
                req.transactionAmount(),
                req.productCode(),
                req.sourceModule(),
                req.sourceRef(),
                req.branchCode());
        if (result == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    null, "No charge applicable"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                ChargeResponse.from(result)));
    }

    /** Waive a previously levied charge. */
    @PostMapping("/waive")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<WaiverResponse>>
            waiveCharge(@RequestBody WaiverRequest req) {
        ChargeTransaction ct = chargeEngine.waiveCharge(
                req.chargeTransactionId(), req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                new WaiverResponse(ct.getId(),
                        ct.getEventType().name(),
                        ct.getTotalDebit(),
                        ct.getWaivedBy(),
                        ct.getWaiverReason())));
    }

    /** Get charge history for an account. */
    @GetMapping("/history/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ChargeHistoryItem>>>
            getChargeHistory(
                    @PathVariable String accountNumber,
                    @RequestParam String fromDate,
                    @RequestParam String toDate) {
        String tid = TenantContext.getCurrentTenant();
        var charges = chargeTxnRepo
                .findByTenantIdAndAccountNumberAndValueDateBetweenOrderByPostedAtAsc(
                        tid, accountNumber,
                        LocalDate.parse(fromDate),
                        LocalDate.parse(toDate));
        var items = charges.stream()
                .map(c -> new ChargeHistoryItem(
                        c.getId(),
                        c.getEventType().name(),
                        c.getBaseFee(),
                        c.getCgstAmount().add(
                                c.getSgstAmount()),
                        c.getTotalDebit(),
                        c.isWaived(),
                        c.getValueDate().toString(),
                        c.getSourceModule(),
                        c.getSourceRef()))
                .toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Request DTOs ===

    public record LevyRequest(
            String eventType,
            String accountNumber,
            String customerGlCode,
            BigDecimal transactionAmount,
            String productCode,
            String sourceModule,
            String sourceRef,
            String branchCode) {}

    public record WaiverRequest(
            Long chargeTransactionId,
            String reason) {}

    // === Response DTOs ===

    public record ChargeResponse(
            Long chargeDefinitionId,
            BigDecimal baseFee,
            BigDecimal gstAmount,
            BigDecimal totalDebit,
            Long journalEntryId,
            String voucherNumber) {
        static ChargeResponse from(ChargeResult r) {
            return new ChargeResponse(
                    r.chargeDefinitionId(),
                    r.baseFee(),
                    r.totalGst(),
                    r.totalDebit(),
                    r.journalEntryId(),
                    r.voucherNumber());
        }
    }

    public record WaiverResponse(
            Long chargeTransactionId,
            String eventType,
            BigDecimal totalWaived,
            String waivedBy,
            String reason) {}

    public record ChargeHistoryItem(
            Long id, String eventType,
            BigDecimal baseFee,
            BigDecimal gstAmount,
            BigDecimal totalDebit,
            boolean waived,
            String valueDate,
            String sourceModule,
            String sourceRef) {}
}
