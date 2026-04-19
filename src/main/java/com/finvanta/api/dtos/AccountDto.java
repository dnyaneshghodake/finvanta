package com.finvanta.api.dtos;

import java.time.LocalDateTime;

/**
 * Account summary DTO for list view
 */
public record AccountDto(
        String id,
        String accountNumber,
        String accountType,
        java.math.BigDecimal balance,
        java.math.BigDecimal availableBalance,
        String status,
        String currency,
        LocalDateTime createdAt) {
}

