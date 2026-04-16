package com.finvanta.api;

import com.finvanta.charge.ChargeKernel;
import com.finvanta.charge.ChargeResult;
import com.finvanta.domain.entity.ChargeTransaction;
import com.finvanta.domain.enums.ChargeEventType;
import com.finvanta.repository.ChargeTransactionRepository;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Charge/Fee REST API per Finacle CHG_API / Temenos FT.COMMISSION.
 *
 * <p>Thin orchestration layer over {@link ChargeKernel}.
 * Per Finacle API standards: request DTOs, response DTOs, role-based access.
 *
 * <p>CBS Role Matrix for Charges:
 * <ul>
 *   <li>MAKER   -> levy charge (via clearing/loan module -- not direct)</li>
 *   <li>CHECKER -> waive or reverse charge</li>
 *   <li>ADMIN   -> all operations + charge definition management</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/charges")
public class ChargeController {

    private final ChargeKernel chargeKernel;
    private final ChargeTransactionRepository chargeTxnRepo;

    public ChargeController(
            ChargeKernel chargeKernel,
            ChargeTransactionRepository chargeTxnRepo) {
        this.chargeKernel = chargeKernel;
        this.chargeTxnRepo = chargeTxnRepo;
    }

    /** Levy a charge on a customer account. */
    @PostMapping("/levy")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ChargeResponse>>
            levyCharge(@Valid @RequestBody LevyRequest req) {
        ChargeResult result = chargeKernel.levyCharge(
                ChargeEventType.valueOf(req.eventType()),
                req.accountNumber(),
                req.customerGlCode(),
                req.transactionAmount(),
                req.productCode(),
                req.sourceModule(),
                req.sourceRef(),
                req.branchCode(),
                req.customerStateCode());
        if (result == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    null, "No charge applicable"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                ChargeResponse.from(result)));
    }

    /** Waive a previously levied charge (policy-driven income giveup). */
    @PostMapping("/waive")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<WaiverResponse>>
            waiveCharge(@Valid @RequestBody WaiverRequest req) {
        ChargeTransaction ct = chargeKernel.waiveCharge(
                req.chargeTransactionId(), req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                new WaiverResponse(ct.getId(),
                        ct.getEventType().name(),
                        ct.getTotalDebit(),
                        ct.getWaivedBy(),
                        ct.getWaiverReason())));
    }

    /**
     * Reverse a previously levied charge (operational rollback).
     * Posts a symmetric contra journal per RBI Fair Practices Code 2023 §5.7.
     */
    @PostMapping("/reverse")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ReversalResponse>>
            reverseCharge(@Valid @RequestBody ReversalRequest req) {
        ChargeTransaction ct = chargeKernel.reverseCharge(
                req.chargeTransactionId(), req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                new ReversalResponse(
                        ct.getId(),
                        ct.getEventType().name(),
                        ct.getTotalDebit(),
                        ct.getReversedBy(),
                        ct.getReversalReason(),
                        ct.getReversalVoucherNumber())));
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
                        c.getStatus().name(),
                        c.getBaseFee(),
                        // CBS: Total GST = CGST + SGST + IGST. Intra-state supplies carry
                        // CGST/SGST and IGST=0; inter-state supplies carry IGST only (per
                        // GST Act 2017 §8 / §12). Summing all three is the only way to get
                        // the correct total regardless of intra-/inter-state split.
                        nz(c.getCgstAmount())
                                .add(nz(c.getSgstAmount()))
                                .add(nz(c.getIgstAmount())),
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

    // CBS: customerStateCode is optional — null/blank falls back to intra-state
    // (CGST + SGST) per GST Act 2017 §12 conservative default.
    public record LevyRequest(
            @NotBlank String eventType,
            @NotBlank String accountNumber,
            @NotBlank String customerGlCode,
            @NotNull @Positive BigDecimal transactionAmount,
            String productCode,
            @NotBlank String sourceModule,
            @NotBlank String sourceRef,
            @NotBlank String branchCode,
            String customerStateCode) {}

    public record WaiverRequest(
            @NotNull Long chargeTransactionId,
            @NotBlank String reason) {}

    public record ReversalRequest(
            @NotNull Long chargeTransactionId,
            @NotBlank String reason) {}

    // === Response DTOs ===

    /**
     * Levy response with full GST breakdown per GST Act 2017 §5/§8.
     *
     * <p>Fields: chargeTransactionId (for waiver/reversal), cgstAmount (intra-state 9%),
     * sgstAmount (intra-state 9%), igstAmount (inter-state 18%), gstAmount (total GST
     * for backward-compat), interState flag, journalEntryId, voucherNumber.
     */
    public record ChargeResponse(
            Long chargeDefinitionId,
            Long chargeTransactionId,
            BigDecimal baseFee,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal gstAmount,
            BigDecimal totalDebit,
            boolean interState,
            Long journalEntryId,
            String voucherNumber) {
        static ChargeResponse from(ChargeResult r) {
            return new ChargeResponse(
                    r.chargeDefinitionId(),
                    r.chargeTransactionId(),
                    r.baseFee(),
                    r.cgstAmount(),
                    r.sgstAmount(),
                    r.igstAmount(),
                    r.totalGst(),
                    r.totalDebit(),
                    r.isInterState(),
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

    public record ReversalResponse(
            Long chargeTransactionId,
            String eventType,
            BigDecimal totalReversed,
            String reversedBy,
            String reason,
            String reversalVoucherNumber) {}

    // CBS: status = LEVIED | WAIVED | REVERSED per ChargeTransactionStatus.
    // waived is deprecated — use status instead. Kept for backward compat.
    public record ChargeHistoryItem(
            Long id, String eventType,
            String status,
            BigDecimal baseFee,
            BigDecimal gstAmount,
            BigDecimal totalDebit,
            boolean waived,
            String valueDate,
            String sourceModule,
            String sourceRef) {}

    /** Null-safe BigDecimal accessor -- treats null as ZERO for GST aggregation. */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
