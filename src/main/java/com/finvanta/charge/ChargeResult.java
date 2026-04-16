package com.finvanta.charge;

import java.math.BigDecimal;

/**
 * CBS Charge Calculation Result per Finacle CHG_ENGINE / Temenos FT.COMMISSION.
 *
 * <p>Returned by {@code ChargeKernel.levyCharge(...)} to the calling module with
 * the complete fee breakdown including the GST Act 2017 §5/§8 intra-state vs
 * inter-state split. The caller can use this to:
 * <ul>
 *   <li>Display the charge to the customer (narration on statement)</li>
 *   <li>Link the charge journal to the source transaction</li>
 *   <li>Include charge amount in transaction confirmation messages</li>
 *   <li>Populate GST ITC data for downstream reconciliation</li>
 *   <li>Reference the {@code chargeTransactionId} for later reversal/waiver</li>
 * </ul>
 *
 * @param chargeDefinitionId FK to the {@code ChargeDefinition} used
 * @param chargeTransactionId FK to the persisted {@code ChargeTransaction} (enables reversal)
 * @param baseFee            Base fee amount (before GST)
 * @param cgstAmount         CGST component (intra-state, 9% of baseFee, else zero)
 * @param sgstAmount         SGST component (intra-state, 9% of baseFee, else zero)
 * @param igstAmount         IGST component (inter-state, 18% of baseFee, else zero)
 * @param totalDebit         Total debited from customer (baseFee + CGST + SGST + IGST)
 * @param journalEntryId     GL journal entry ID for the charge posting
 * @param voucherNumber      Voucher number from {@code TransactionEngine}
 */
public record ChargeResult(
        Long chargeDefinitionId,
        Long chargeTransactionId,
        BigDecimal baseFee,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal totalDebit,
        Long journalEntryId,
        String voucherNumber) {

    /** Total GST (CGST + SGST + IGST). Exactly one of (CGST+SGST) or IGST is non-zero. */
    public BigDecimal totalGst() {
        return cgstAmount.add(sgstAmount).add(igstAmount);
    }

    /** {@code true} iff this charge was classified as an inter-state supply (IGST only). */
    public boolean isInterState() {
        return igstAmount.signum() > 0;
    }
}
