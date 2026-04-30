package com.finvanta.cbs.modules.teller.validator;

import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;
import com.finvanta.cbs.modules.teller.dto.request.DenominationEntry;
import com.finvanta.util.BusinessException;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * CBS Denomination Validator per RBI Currency Management / CBS DENOMS standard.
 *
 * <p>Enforces the central invariant of teller cash transactions:
 * <pre>
 *   amount == SUM over rows of (denomination.value() * (unitCount + counterfeitCount))
 * </pre>
 *
 * <p>Counterfeit notes are physically present in the bundle the customer
 * tendered, so they MUST be included in the sum check -- otherwise an operator
 * could "make the math work" by classifying genuine notes as counterfeit (or
 * vice versa) and the totals would silently match. The deposit is rejected
 * separately when any {@code counterfeitCount > 0}; this validator's only job
 * is the equality check.
 *
 * <p>Validator is stateless and thread-safe. Threadlocal-free, no caches --
 * one instance per Spring context.
 */
@Component
public class DenominationValidator {

    /**
     * Validates that the denomination breakdown sums exactly to {@code amount}
     * (including counterfeit-counted units, see class Javadoc for rationale)
     * and that no row carries an unrecognized denomination.
     *
     * <p>{@code BigDecimal.compareTo} is used (not {@code equals}) so scale
     * differences -- e.g. INR 50000.00 vs INR 50000 -- do not cause spurious
     * rejections.
     *
     * @throws BusinessException CBS-TELLER-005 on null/unrecognized denomination
     * @throws BusinessException CBS-TELLER-004 on sum mismatch
     */
    public void validateSum(List<DenominationEntry> rows, BigDecimal amount) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(CbsErrorCodes.TELLER_DENOM_SUM_MISMATCH,
                    "Denomination breakdown is required for teller cash transactions");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(CbsErrorCodes.TELLER_DENOM_SUM_MISMATCH,
                    "Transaction amount must be positive for denomination matching");
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (DenominationEntry row : rows) {
            if (row == null || row.denomination() == null) {
                throw new BusinessException(CbsErrorCodes.TELLER_DENOM_INVALID,
                        "Each denomination row must specify a denomination");
            }
            // Defensive re-check the enum is registered. Jackson can deserialize
            // arbitrary strings if @JsonCreator-aware factories are added later;
            // this guard ensures we always operate on the canonical enum set.
            try {
                IndianCurrencyDenomination.valueOf(row.denomination().name());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(CbsErrorCodes.TELLER_DENOM_INVALID,
                        "Unrecognized denomination: " + row.denomination());
            }
            // Counterfeit count is part of the physical bundle the customer
            // tendered, so it counts toward the sum (see class Javadoc).
            long totalUnits = row.unitCount() + row.counterfeitCount();
            sum = sum.add(row.denomination().totalFor(totalUnits));
        }

        if (sum.compareTo(amount) != 0) {
            throw new BusinessException(CbsErrorCodes.TELLER_DENOM_SUM_MISMATCH,
                    "Denomination breakdown INR " + sum
                            + " does not match transaction amount INR " + amount
                            + ". Recount the cash and retry.");
        }
    }

    /**
     * Returns true if any row in the breakdown carries a non-zero counterfeit
     * count. Used by the service layer to route the deposit to FICN review
     * instead of crediting the customer account.
     */
    public boolean hasCounterfeit(List<DenominationEntry> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        for (DenominationEntry row : rows) {
            if (row != null && row.counterfeitCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Coalesces multiple rows for the same denomination into one. The teller UI
     * may submit duplicates accidentally (e.g., two rows for NOTE_500). This
     * helper normalizes so downstream persistence has at most one
     * {@code CashDenomination} row per denomination per transaction.
     */
    public Map<IndianCurrencyDenomination, MergedRow> coalesce(List<DenominationEntry> rows) {
        Map<IndianCurrencyDenomination, MergedRow> merged =
                new EnumMap<>(IndianCurrencyDenomination.class);
        if (rows == null) {
            return merged;
        }
        for (DenominationEntry row : rows) {
            if (row == null || row.denomination() == null) continue;
            // Skip pure-zero rows so the teller UI can post a complete grid
            // (with zeros for unused denominations) without bloating the DB.
            if (row.unitCount() == 0 && row.counterfeitCount() == 0) continue;
            merged.merge(
                    row.denomination(),
                    new MergedRow(row.unitCount(), row.counterfeitCount()),
                    MergedRow::plus);
        }
        return merged;
    }

    /** Coalesce intermediate value-class. */
    public record MergedRow(long unitCount, long counterfeitCount) {
        public MergedRow plus(MergedRow other) {
            return new MergedRow(
                    this.unitCount + other.unitCount,
                    this.counterfeitCount + other.counterfeitCount);
        }
    }
}
