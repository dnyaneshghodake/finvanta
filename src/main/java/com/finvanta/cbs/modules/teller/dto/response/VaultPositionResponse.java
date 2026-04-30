package com.finvanta.cbs.modules.teller.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS Vault Position Response DTO per Tier-1 DTO isolation standards.
 *
 * <p>Immutable projection of {@code VaultPosition} for API consumers (BFF + JSP).
 * Replaces the prior practice of returning the JPA entity directly from the
 * vault REST endpoints, which exposed Hibernate proxies, the {@code @Version}
 * field, and the lazy {@code Branch} relation -- the C3 entity-exposure
 * finding from {@code CBS_TIER1_AUDIT_REPORT.md}.
 *
 * <p>Field grouping mirrors the printable layout for the EOD vault sheet:
 * top (identity), middle (balances), bottom (close-time variance and
 * custodian sign-offs).
 */
public record VaultPositionResponse(
        Long id,
        String branchCode,
        String branchName,
        LocalDate businessDate,
        String status,
        BigDecimal openingBalance,
        BigDecimal currentBalance,
        BigDecimal countedBalance,
        BigDecimal varianceAmount,
        String openedBy,
        String closedBy,
        String remarks
) {
}
