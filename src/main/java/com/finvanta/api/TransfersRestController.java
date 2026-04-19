package com.finvanta.api;

import com.finvanta.api.dtos.TransferRequestDto;
import com.finvanta.api.dtos.TransferResponseDto;
import com.finvanta.api.dtos.TransferStatusDto;
import com.finvanta.domain.entity.Transfer;
import com.finvanta.service.TransferService;
import com.finvanta.util.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;

import java.time.LocalDateTime;

/**
 * CBS REST API Controller - Fund Transfers
 *
 * Per RBI IT Governance Direction 2023 §8.4:
 * - All transfers must be executed through TransactionEngine (GL posting enforced)
 * - OTP verification required for all inter-account transfers
 * - Transfer status changes must be published via WebSocket in real-time
 * - Complete audit trail with GL reference numbers
 *
 * Endpoints:
 *   POST   /api/v1/transfers                        → Initiate transfer (returns OTP)
 *   POST   /api/v1/transfers/{transferId}/verify-otp    → Execute transfer
 *   GET    /api/v1/transfers/{transferId}           → Get transfer status
 *
 * CBS CRITICAL INVARIANT:
 * - No transfer can bypass TransactionEngine
 * - OTP verification is MANDATORY (no exceptions)
 * - GL posting must be ATOMIC (no partial credits/debits)
 * - Both accounts must be locked during execution
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransfersRestController {

    private static final Logger log = LoggerFactory.getLogger(TransfersRestController.java);

    private final TransferService transferService;

    public TransfersRestController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /api/v1/transfers - Initiate fund transfer
     *
     * Flow:
     *   1. Validate request (account exists, balance sufficient, limits not exceeded)
     *   2. Create Transfer entity in INITIATED status
     *   3. Request OTP (send to registered email/SMS)
     *   4. Return OTP ID for React frontend to display verification screen
     *
     * Request:
     *   {
     *     "fromAccountId": "acc-123",
     *     "toAccountNumber": "1234567890",
     *     "amount": 10000,
     *     "description": "Payment to vendor"
     *   }
     *
     * Response (202 Accepted):
     *   {
     *     "status": "SUCCESS",
     *     "data": {
     *       "transferId": "transfer-456",
     *       "amount": 10000,
     *       "status": "PENDING_VERIFICATION",
     *       "otpId": "otp-789",
     *       "message": "OTP sent to registered email"
     *     }
     *   }
     *
     * Error Codes:
     *   ACCOUNT_NOT_FOUND         → Account doesn't exist
     *   INSUFFICIENT_BALANCE      → Not enough balance
     *   DAILY_LIMIT_EXCEEDED      → Transfer limit exceeded
     *   INVALID_BENEFICIARY       → Destination account invalid
     *
     * CBS SECURITY:
     * - Transfer ID is UUID (not sequential, prevents enumeration)
     * - OTP is sent asynchronously (doesn't block response)
     * - Transfer locked for 10 minutes (OTP validity period)
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<TransferResponseDto>> initiateTransfer(
            @Valid @RequestBody TransferRequestDto req,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            log.info("Transfer initiation requested: tenant={}, from={}, to={}, amount={}",
                tenant, req.fromAccountId(), req.toAccountNumber(), req.amount());

            // Call service layer to initiate transfer and validate
            TransferResponseDto response = transferService.initiateTransfer(tenant, req);

            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponseV2.success(response,
                    "Transfer initiated. Please verify with OTP."));

        } catch (com.finvanta.util.BusinessException ex) {
            log.warn("Transfer initiation failed: {}", ex.getErrorCode());
            return ResponseEntity.status(mapErrorToStatus(ex.getErrorCode()))
                .body(ApiResponseV2.error(ex.getErrorCode(), ex.getMessage()));

        } catch (Exception ex) {
            log.error("Error initiating transfer: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("TRANSFER_INITIATION_FAILED",
                    "Failed to initiate transfer. Try again."));
        }
    }

    /**
     * POST /api/v1/transfers/{transferId}/verify-otp - Verify OTP and execute transfer
     *
     * Flow:
     *   1. Verify OTP (check validity, not used before, not expired)
     *   2. Lock both FROM and TO accounts (pessimistic locking)
     *   3. Validate balance one final time (just before GL posting)
     *   4. Execute through TransactionEngine (GL posting ATOMIC transaction)
     *   5. Mark transfer as COMPLETED
     *   6. Publish WebSocket updates to both accounts
     *   7. Return confirmation with GL reference number
     *
     * Request:
     *   {
     *     "otpCode": "123456"
     *   }
     *
     * Response (200 OK):
     *   {
     *     "status": "SUCCESS",
     *     "data": {
     *       "transferId": "transfer-456",
     *       "amount": 10000,
     *       "status": "COMPLETED",
     *       "glReferenceNumber": "REF-2024-001",
     *       "completedAt": "2024-01-20T10:35:00"
     *     },
     *     "message": "Transfer completed successfully"
     *   }
     *
     * Error Codes:
     *   INVALID_OTP                 → OTP incorrect or expired
     *   OTP_ALREADY_USED            → OTP was already used (fraud detection)
     *   TRANSFER_NOT_FOUND          → Transfer ID invalid
     *   INSUFFICIENT_BALANCE        → Balance changed since initiation
     *   TRANSFER_TIMEOUT            → OTP validity period expired (10 min)
     *
     * CBS CRITICAL INVARIANT:
     * - If GL posting succeeds but OTP marking fails → manual recovery required
     * - If GL posting fails → transfer marked as FAILED (can retry)
     * - No refunds are automatic (manual intervention required)
     */
    @PostMapping("/{transferId}/verify-otp")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<TransferStatusDto>> verifyOtpAndExecute(
            @PathVariable String transferId,
            @Valid @RequestBody VerifyOtpRequestDto req,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            log.info("Transfer OTP verification requested: tenant={}, transferId={}",
                tenant, transferId);

            // Call service layer to verify OTP and execute
            TransferStatusDto response = transferService.verifyOtpAndExecute(tenant, transferId, req.otpCode());

            return ResponseEntity.ok(
                ApiResponseV2.success(response, "Transfer completed successfully"));

        } catch (com.finvanta.util.BusinessException ex) {
            log.warn("Transfer execution failed: transferId={}, error={}",
                transferId, ex.getErrorCode());
            return ResponseEntity.status(mapErrorToStatus(ex.getErrorCode()))
                .body(ApiResponseV2.error(ex.getErrorCode(), ex.getMessage()));

        } catch (Exception ex) {
            log.error("Error executing transfer: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("TRANSFER_EXECUTION_FAILED",
                    "Failed to execute transfer. Contact support."));
        }
    }

    /**
     * GET /api/v1/transfers/{transferId} - Get transfer status
     *
     * Returns current transfer status and GL details.
     * Can be called multiple times without side effects (idempotent).
     *
     * Response:
     *   {
     *     "status": "SUCCESS",
     *     "data": {
     *       "transferId": "transfer-456",
     *       "amount": 10000,
     *       "status": "COMPLETED",
     *       "glReferenceNumber": "REF-2024-001",
     *       "initiatedAt": "2024-01-20T10:30:00",
     *       "completedAt": "2024-01-20T10:35:00"
     *     }
     *   }
     *
     * Status Values:
     *   INITIATED              → Waiting for OTP
     *   PENDING_VERIFICATION   → OTP sent, awaiting verification
     *   GL_POSTING             → Executing GL transaction
     *   COMPLETED              → Successfully posted to GL
     *   FAILED                 → GL posting failed (can retry OTP)
     *   EXPIRED                → OTP validity period expired
     *   CANCELLED              → User cancelled transfer
     */
    @GetMapping("/{transferId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<TransferStatusDto>> getTransferStatus(
            @PathVariable String transferId,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            TransferStatusDto response = transferService.getTransferStatus(tenant, transferId);

            if (response == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseV2.error("TRANSFER_NOT_FOUND",
                        "Transfer not found"));
            }

            return ResponseEntity.ok(ApiResponseV2.success(response));

        } catch (Exception ex) {
            log.error("Error getting transfer status: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR",
                    "Failed to get transfer status"));
        }
    }

    // === Helper Methods ===

    /**
     * Map CBS error codes to HTTP status codes
     */
    private HttpStatus mapErrorToStatus(String errorCode) {
        return switch (errorCode) {
            case "ACCOUNT_NOT_FOUND",
                 "TRANSFER_NOT_FOUND",
                 "OTP_NOT_FOUND" ->
                HttpStatus.NOT_FOUND;

            case "INSUFFICIENT_BALANCE",
                 "ACCOUNT_FROZEN",
                 "ACCOUNT_CLOSED",
                 "DAILY_LIMIT_EXCEEDED" ->
                HttpStatus.UNPROCESSABLE_ENTITY;

            case "INVALID_OTP",
                 "OTP_ALREADY_USED",
                 "TRANSFER_TIMEOUT" ->
                HttpStatus.UNAUTHORIZED;

            case "TRANSFER_NOT_FOUND",
                 "INVALID_BENEFICIARY" ->
                HttpStatus.BAD_REQUEST;

            default ->
                HttpStatus.BAD_REQUEST;
        };
    }

    // === Request/Response DTOs ===

    /**
     * OTP verification request
     */
    public record VerifyOtpRequestDto(
            String otpCode) {
    }
}

