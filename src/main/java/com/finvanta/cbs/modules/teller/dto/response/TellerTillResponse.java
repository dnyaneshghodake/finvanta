package com.finvanta.cbs.modules.teller.dto.response;

import com.finvanta.cbs.modules.teller.domain.TellerTillStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Teller Till Response DTO per CBS TELLER_INQ standard.
 *
 * <p>Immutable projection of {@code TellerTill} for API consumers (BFF + JSP).
 * Does NOT carry the JPA {@code version} or any internal Hibernate state.
 *
 * <p>The {@code tellerUserId} field surfaces the username (not just the
 * internal user PK) so the BFF can render "Till of <user>" labels without an
 * extra user-lookup round-trip. Branch code/name are similarly denormalized
 * to avoid N+1 from the JSP statement-printing path.
 */
public record TellerTillResponse(
        Long id,
        String tellerUserId,
        String branchCode,
        String branchName,
        LocalDate businessDate,
        TellerTillStatus status,
        BigDecimal openingBalance,
        BigDecimal currentBalance,
        BigDecimal countedBalance,
        BigDecimal varianceAmount,
        BigDecimal tillCashLimit,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        String openedBySupervisor,
        String closedBySupervisor,
        String remarks
) {
}
