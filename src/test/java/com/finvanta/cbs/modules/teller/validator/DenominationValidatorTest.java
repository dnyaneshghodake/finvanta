package com.finvanta.cbs.modules.teller.validator;

import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;
import com.finvanta.cbs.modules.teller.dto.request.DenominationEntry;
import com.finvanta.util.BusinessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link DenominationValidator}.
 *
 * <p>The validator has no collaborators, so no Mockito wiring -- just bring
 * up an instance and exercise the contract. Each test asserts a single
 * Tier-1 invariant documented on the validator class.
 */
class DenominationValidatorTest {

    private DenominationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DenominationValidator();
    }

    @Test
    @DisplayName("validateSum: passes when notes sum exactly to amount")
    void validateSum_happyPath_notesOnly() {
        // 5 x 500 + 3 x 100 = 2800
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 0),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_100, 3, 0));
        assertDoesNotThrow(() -> validator.validateSum(rows, new BigDecimal("2800")));
    }

    @Test
    @DisplayName("validateSum: passes with mix of notes + coin bucket")
    void validateSum_happyPath_notesAndCoins() {
        // 1 x 2000 + 1 x 500 + 250 (coin bucket value) = 2750
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_2000, 1, 0),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 1, 0),
                new DenominationEntry(IndianCurrencyDenomination.COIN_BUCKET, 250, 0));
        assertDoesNotThrow(() -> validator.validateSum(rows, new BigDecimal("2750")));
    }

    @Test
    @DisplayName("validateSum: ignores BigDecimal scale (50000.00 == 50000)")
    void validateSum_ignoresScaleDifferences() {
        // Caller may pass either scale; compareTo not equals.
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 100, 0));
        assertDoesNotThrow(() -> validator.validateSum(rows, new BigDecimal("50000.00")));
        assertDoesNotThrow(() -> validator.validateSum(rows, new BigDecimal("50000")));
    }

    @Test
    @DisplayName("validateSum: counterfeit-counted units count toward the sum")
    void validateSum_counterfeitCountsTowardSum() {
        // Customer tendered 5 genuine 500s + 1 counterfeit 500 = 6 notes physical
        // worth INR 3000. The deposit will be REJECTED later because counterfeit > 0,
        // but the sum check itself is on the physical bundle.
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 1));
        assertDoesNotThrow(() -> validator.validateSum(rows, new BigDecimal("3000")));
    }

    @Test
    @DisplayName("validateSum: rejects with CBS-TELLER-004 when sum less than amount")
    void validateSum_rejectsUnderShoot() {
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 0));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateSum(rows, new BigDecimal("3000")));
        assertEquals("CBS-TELLER-004", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSum: rejects with CBS-TELLER-004 when sum exceeds amount")
    void validateSum_rejectsOverShoot() {
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 10, 0));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateSum(rows, new BigDecimal("3000")));
        assertEquals("CBS-TELLER-004", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSum: rejects empty denomination list")
    void validateSum_rejectsEmpty() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateSum(List.of(), new BigDecimal("100")));
        assertEquals("CBS-TELLER-004", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSum: rejects null amount and zero/negative amount")
    void validateSum_rejectsBadAmount() {
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 1, 0));
        assertThrows(BusinessException.class,
                () -> validator.validateSum(rows, null));
        assertThrows(BusinessException.class,
                () -> validator.validateSum(rows, BigDecimal.ZERO));
        assertThrows(BusinessException.class,
                () -> validator.validateSum(rows, new BigDecimal("-1")));
    }

    @Test
    @DisplayName("hasCounterfeit: returns true when any row has a counterfeit count")
    void hasCounterfeit_detectsAnyRow() {
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 0),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_2000, 1, 1));
        assertTrue(validator.hasCounterfeit(rows));
    }

    @Test
    @DisplayName("hasCounterfeit: returns false when no row has a counterfeit count")
    void hasCounterfeit_negativeCase() {
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 0),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_100, 3, 0));
        assertFalse(validator.hasCounterfeit(rows));
    }

    @Test
    @DisplayName("hasCounterfeit: handles null and empty lists")
    void hasCounterfeit_nullAndEmpty() {
        assertFalse(validator.hasCounterfeit(null));
        assertFalse(validator.hasCounterfeit(List.of()));
    }

    @Test
    @DisplayName("coalesce: merges duplicate denomination rows")
    void coalesce_mergesDuplicates() {
        // Operator (or buggy UI) submitted NOTE_500 twice
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 3, 0),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 2, 1),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_100, 4, 0));
        Map<IndianCurrencyDenomination, DenominationValidator.MergedRow> merged =
                validator.coalesce(rows);

        assertEquals(2, merged.size());
        DenominationValidator.MergedRow merged500 =
                merged.get(IndianCurrencyDenomination.NOTE_500);
        assertEquals(5L, merged500.unitCount());
        assertEquals(1L, merged500.counterfeitCount());
    }

    @Test
    @DisplayName("coalesce: filters zero-count rows")
    void coalesce_skipsZeroRows() {
        List<DenominationEntry> rows = List.of(
                new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 0, 0),
                new DenominationEntry(IndianCurrencyDenomination.NOTE_100, 4, 0));
        Map<IndianCurrencyDenomination, DenominationValidator.MergedRow> merged =
                validator.coalesce(rows);
        assertEquals(1, merged.size());
        assertTrue(merged.containsKey(IndianCurrencyDenomination.NOTE_100));
    }

    @Test
    @DisplayName("DenominationEntry: rejects coin denominations with non-zero counterfeit count")
    void denominationEntry_rejectsCoinCounterfeit() {
        // Per RBI Currency Management: coins are not subject to FICN reporting.
        // The compact constructor on DenominationEntry must reject the construction.
        assertThrows(IllegalArgumentException.class,
                () -> new DenominationEntry(IndianCurrencyDenomination.COIN_BUCKET, 100, 5));
    }

    @Test
    @DisplayName("IndianCurrencyDenomination.totalFor: rejects negative count")
    void enum_totalFor_rejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> IndianCurrencyDenomination.NOTE_500.totalFor(-1));
    }

    @Test
    @DisplayName("IndianCurrencyDenomination.fromValue: maps face value to enum")
    void enum_fromValue_happyPath() {
        assertEquals(IndianCurrencyDenomination.NOTE_2000,
                IndianCurrencyDenomination.fromValue(new BigDecimal("2000")));
        assertEquals(IndianCurrencyDenomination.COIN_BUCKET,
                IndianCurrencyDenomination.fromValue(BigDecimal.ONE));
    }

    @Test
    @DisplayName("IndianCurrencyDenomination.fromValue: rejects unknown rupee value")
    void enum_fromValue_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> IndianCurrencyDenomination.fromValue(new BigDecimal("75")));
    }
}
