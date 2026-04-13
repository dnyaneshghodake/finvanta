package com.finvanta.charge;

import java.math.BigDecimal;

/**
 * CBS Charge Calculation Result per Finacle CHG_ENGINE.
 *
 * Returned by ChargeEngine.levyCharge() to the calling module with the
 * complete fee breakdown. The caller can use this to:
 * - Display the charge to the customer (narration on statement)
 * - Link the charge journal to the source transaction
 * - Include charge amount in transaction confirmation messages
 *
 * @param chargeDefinitionId FK to the ChargeDefinition used
 * @param baseFee            Base fee amount (before GST)
 * @param cgstAmount         CGST component (9% of baseFee)
 * @param sgstAmount         SGST component (9% of baseFee)
 * @param totalDebit         Total debited from customer (baseFee + CGST + SGST)
 * @param journalEntryId     GL journal entry ID for the charge posting
 * @param voucherNumber      Voucher number from TransactionEngine
 */
public record ChargeResult(
        Long chargeDefinitionId,
        BigDecimal baseFee,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal totalDebit,
        Long journalEntryId,
        String voucherNumber) {

    /** Total GST (CGST + SGST) */
    public BigDecimal totalGst() {
        return cgstAmount.add(sgstAmount);
    }
}
