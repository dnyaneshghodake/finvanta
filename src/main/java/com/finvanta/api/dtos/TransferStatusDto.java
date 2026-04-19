package com.finvanta.api.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transfer status DTO
 */
public record TransferStatusDto(
        String transferId,
        BigDecimal amount,
        String status,
        String glReferenceNumber,
        LocalDateTime initiatedAt,
        LocalDateTime completedAt) {
}

