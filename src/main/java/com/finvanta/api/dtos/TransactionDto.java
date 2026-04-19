package com.finvanta.api.dtos;

import java.time.LocalDateTime;

/**
 * Transaction record DTO for transaction list
 */
public record TransactionDto(
        String id,
        String transactionId,
        java.math.BigDecimal amount,
        String type,
        String status,
        String description,
        LocalDateTime postingDate,
        LocalDateTime valueDate,
        String referenceNumber,
        String beneficiaryName) {
}

