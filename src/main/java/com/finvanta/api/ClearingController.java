package com.finvanta.api;

import com.finvanta.batch.ClearingEngine;
import com.finvanta.domain.entity.ClearingCycle;
import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.enums.PaymentRail;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Clearing REST API per Finacle CLG_API / Temenos IRIS Clearing.
 *
 * Thin orchestration layer over ClearingEngine — no business logic here.
 * Per Finacle API standards:
 * - Request DTOs for input validation (no entity exposure)
 * - Standardized ApiResponse envelope for all responses
 * - Role-based access via @PreAuthorize
 * - Branch-scoped access enforced by ClearingEngine internally
 *
 * CBS Role Matrix for Clearing:
 *   MAKER   → initiateOutward, processInward
 *   CHECKER → approveOutward, confirmSettlement
 *   ADMIN   → reverse, return, cycle management, all MAKER/CHECKER ops
 */
@RestController
@RequestMapping("/api/v1/clearing")
public class ClearingController {

    private final ClearingEngine clearingEngine;

    public ClearingController(ClearingEngine clearingEngine) {
        this.clearingEngine = clearingEngine;
    }

    // === Outward Clearing ===

    @PostMapping("/outward")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            initiateOutward(
                    @RequestBody OutwardRequest req) {
        ClearingTransaction ct =
                clearingEngine.initiateOutward(
                        req.extRef(),
                        PaymentRail.valueOf(req.rail()),
                        req.amount(),
                        req.customerAccount(),
                        req.counterpartyIfsc(),
                        req.counterpartyAccount(),
                        req.counterpartyName(),
                        req.narration(),
                        req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    @PostMapping("/outward/approve")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            approveOutward(
                    @RequestBody ApproveRequest req) {
        ClearingTransaction ct =
                clearingEngine.approveOutward(
                        req.extRef(),
                        req.workflowId(),
                        req.remarks());
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    // === Inward Clearing ===

    @PostMapping("/inward")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            processInward(
                    @RequestBody InwardRequest req) {
        ClearingTransaction ct =
                clearingEngine.processInward(
                        req.extRef(), req.utr(),
                        PaymentRail.valueOf(req.rail()),
                        req.amount(),
                        req.beneficiaryAccount(),
                        req.remitterIfsc(),
                        req.remitterAccount(),
                        req.remitterName(),
                        req.narration(),
                        req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    // === Settlement ===

    @PostMapping("/settlement")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            confirmSettlement(
                    @RequestBody SettlementRequest req) {
        ClearingTransaction ct =
                clearingEngine.confirmOutwardSettlement(
                        req.extRef(), req.rbiRef());
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    @PostMapping("/network/send")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            sendToNetwork(
                    @RequestParam String extRef) {
        ClearingTransaction ct =
                clearingEngine.sendToNetwork(extRef);
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    // === Reversal / Return ===

    @PostMapping("/reverse")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            reverse(@RequestBody ReversalRequest req) {
        ClearingTransaction ct =
                clearingEngine.reverseClearingTransaction(
                        req.extRef(), req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    @PostMapping("/inward/return")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingTxnResponse>>
            returnInward(
                    @RequestBody ReversalRequest req) {
        ClearingTransaction ct =
                clearingEngine.returnInward(
                        req.extRef(), req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                ClearingTxnResponse.from(ct)));
    }

    // === NEFT Cycle Management ===

    @PostMapping("/cycle/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CycleResponse>>
            closeCycle(@RequestParam Long cycleId) {
        ClearingCycle cycle =
                clearingEngine.closeClearingCycle(cycleId);
        return ResponseEntity.ok(ApiResponse.success(
                CycleResponse.from(cycle)));
    }

    @PostMapping("/cycle/submit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CycleResponse>>
            submitCycle(@RequestParam Long cycleId) {
        ClearingCycle cycle =
                clearingEngine.submitCycleToRbi(cycleId);
        return ResponseEntity.ok(ApiResponse.success(
                CycleResponse.from(cycle)));
    }

    @PostMapping("/cycle/settle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CycleResponse>>
            settleCycle(
                    @RequestParam Long cycleId,
                    @RequestParam String rbiRef) {
        ClearingCycle cycle =
                clearingEngine.settleCycle(cycleId, rbiRef);
        return ResponseEntity.ok(ApiResponse.success(
                CycleResponse.from(cycle)));
    }

    // === Request DTOs (records — immutable, no setters) ===

    public record OutwardRequest(
            String extRef, String rail,
            BigDecimal amount,
            String customerAccount,
            String counterpartyIfsc,
            String counterpartyAccount,
            String counterpartyName,
            String narration, Long branchId) {}

    public record InwardRequest(
            String extRef, String utr, String rail,
            BigDecimal amount,
            String beneficiaryAccount,
            String remitterIfsc,
            String remitterAccount,
            String remitterName,
            String narration, Long branchId) {}

    public record ApproveRequest(
            String extRef, Long workflowId,
            String remarks) {}

    public record SettlementRequest(
            String extRef, String rbiRef) {}

    public record ReversalRequest(
            String extRef, String reason) {}

    // === Response DTOs (no entity exposure) ===

    public record ClearingTxnResponse(
            Long id, String extRef, String utr,
            String rail, String direction,
            BigDecimal amount, String status,
            String customerAccount,
            String branchCode,
            String initiatedAt,
            String completedAt) {
        static ClearingTxnResponse from(
                ClearingTransaction ct) {
            return new ClearingTxnResponse(
                    ct.getId(),
                    ct.getExternalRefNo(),
                    ct.getUtrNumber(),
                    ct.getPaymentRail().getCode(),
                    ct.getDirection().name(),
                    ct.getAmount(),
                    ct.getStatus().name(),
                    ct.getCustomerAccountRef(),
                    ct.getBranchCode(),
                    ct.getInitiatedAt() != null
                            ? ct.getInitiatedAt().toString()
                            : null,
                    ct.getCompletedAt() != null
                            ? ct.getCompletedAt().toString()
                            : null);
        }
    }

    public record CycleResponse(
            Long id, String rail, String cycleDate,
            int cycleNumber, String status,
            BigDecimal netObligation,
            int transactionCount) {
        static CycleResponse from(ClearingCycle c) {
            return new CycleResponse(
                    c.getId(),
                    c.getRailType().getCode(),
                    c.getCycleDate().toString(),
                    c.getCycleNumber(),
                    c.getStatus().name(),
                    c.getNetObligation(),
                    c.getTransactionCount());
        }
    }
}
