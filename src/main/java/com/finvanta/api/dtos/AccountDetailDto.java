package com.finvanta.api.dtos;

import java.time.LocalDateTime;

/**
 * Detailed account information DTO
 */
public record AccountDetailDto(
        String id,
        String accountNumber,
        String accountType,
        String accountName,
        java.math.BigDecimal balance,
        java.math.BigDecimal availableBalance,
        String status,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime closedAt) {
}

