package com.finvanta.api.dtos;

import java.math.BigDecimal;

/**
 * Transfer response DTO (returned after initiation)
 */
public record TransferResponseDto(
        String transferId,
        BigDecimal amount,
        String status,
        String otpId,
        String message) {
}

