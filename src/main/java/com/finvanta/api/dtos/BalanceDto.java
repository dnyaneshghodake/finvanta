package com.finvanta.api.dtos;

import java.time.LocalDateTime;

/**
 * Current balance DTO for dashboard display
 */
public record BalanceDto(
        String accountId,
        java.math.BigDecimal balance,
        java.math.BigDecimal availableBalance,
        String currency,
        LocalDateTime timestamp) {
}

