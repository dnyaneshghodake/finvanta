package com.finvanta.cbs.modules.teller.dto.request;

import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * CBS Denomination Entry per CBS DENOMS standard.
 *
 * <p>One row of the denomination grid submitted by the teller UI. The full grid
 * is a {@code List<DenominationEntry>} on
 * {@link CashDepositRequest#denominations()} /
 * {@link CashWithdrawalRequest#denominations()}.
 *
 * <p>{@code unitCount} is non-negative -- zero is permitted so the UI can post
 * a complete grid (all denominations, with zeros where unused) without filtering
 * client-side. The validator skips zero rows when summing.
 *
 * <p>For {@code IndianCurrencyDenomination#COIN_BUCKET}, {@code unitCount} carries
 * the rupee VALUE of coins (e.g., {@code unitCount = 250} means INR 250 worth of
 * coins) because per-coin denomination tracking is impractical at the branch
 * counter. See the enum's Javadoc for the rationale.
 *
 * <p>Counterfeit notes are reported via a separate flag so legitimate-tender
 * value and counterfeit-tender value land on different audit trails. A non-zero
 * {@code counterfeitCount} routes the transaction to the FICN workflow per
 * RBI Master Direction on Counterfeit Notes -- the deposit is rejected and an
 * FICN acknowledgement receipt is generated.
 */
public record DenominationEntry(

        @NotNull(message = "Denomination is required")
        IndianCurrencyDenomination denomination,

        @PositiveOrZero(message = "Unit count cannot be negative")
        long unitCount,

        @PositiveOrZero(message = "Counterfeit count cannot be negative")
        long counterfeitCount
) {
    public DenominationEntry {
        // Defensive: a non-zero counterfeit count is meaningful only for paper notes.
        // COIN_BUCKET cannot carry a counterfeit count because coins are not subject
        // to FICN reporting (RBI Currency Management Dept handles damaged coins
        // through a separate withdrawal mechanism).
        if (denomination != null
                && denomination.kind() == IndianCurrencyDenomination.DenominationKind.COIN
                && counterfeitCount > 0) {
            throw new IllegalArgumentException(
                    "Counterfeit count is not applicable to coin denominations");
        }
    }
}
