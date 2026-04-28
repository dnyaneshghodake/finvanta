package com.finvanta.cbs.modules.teller.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * CBS Teller Till Open Request per CBS TELLER_OPEN standard.
 *
 * <p>Initiated by a teller at start-of-shift. The till is created in
 * {@link com.finvanta.cbs.modules.teller.domain.TellerTillStatus#PENDING_OPEN}
 * and routes to a supervisor for sign-off if {@code openingBalance} exceeds the
 * configured branch threshold; otherwise auto-approved to OPEN.
 *
 * <p>{@code branchId} and {@code tellerUserId} are NOT on this DTO -- the
 * service layer derives them from the authenticated principal and their home
 * branch. Allowing the client to submit these would let one teller open another
 * teller's till, breaking RBI Internal Controls / segregation of duties.
 *
 * <p>{@code businessDate} is also derived server-side from
 * {@code BusinessDateService.getCurrentBusinessDate()} for the teller's branch.
 * Per Tier-1 CBS standards, the client MUST NOT supply business date.
 */
public record OpenTillRequest(

        @NotNull(message = "Opening balance is required")
        @PositiveOrZero(message = "Opening balance cannot be negative")
        BigDecimal openingBalance,

        /**
         * Optional per-till soft cash limit. When the till's running balance
         * exceeds this, subsequent cash deposits route to maker-checker even
         * if below the per-transaction CTR threshold. If null, the branch
         * default from TellerConfig applies.
         */
        @PositiveOrZero(message = "Till cash limit cannot be negative")
        BigDecimal tillCashLimit,

        String remarks
) {
}
