package com.finvanta.cbs.modules.teller.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Teller Cash Movement Response DTO per Tier-1 DTO isolation standards.
 *
 * <p>Immutable projection of {@code TellerCashMovement} for API consumers
 * (BFF + JSP). Carries enough information to render the vault dashboard
 * + the custodian's pending-approvals queue without dereferencing any
 * lazy JPA relation outside the {@code @Transactional} boundary.
 *
 * <p>{@code movementType} is the string `"BUY"` (vault → till) or `"SELL"`
 * (till → vault). {@code status} is `"PENDING"`, `"APPROVED"`, or
 * `"REJECTED"`. Both are kept as strings (not enums) to match the
 * underlying entity's storage and to avoid forcing the BFF to ship a
 * client-side enum.
 */
public record TellerCashMovementResponse(
        Long id,
        String movementRef,
        String movementType,
        String branchCode,
        Long tillId,
        Long vaultId,
        LocalDate businessDate,
        BigDecimal amount,
        String status,
        String requestedBy,
        LocalDateTime requestedAt,
        String approvedBy,
        LocalDateTime approvedAt,
        String rejectionReason,
        String remarks
) {
}
