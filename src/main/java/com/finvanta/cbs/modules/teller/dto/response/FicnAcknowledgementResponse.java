package com.finvanta.cbs.modules.teller.dto.response;

import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CBS FICN Acknowledgement Receipt DTO per RBI Master Direction on
 * Counterfeit Notes.
 *
 * <p>This is the receipt the bank prints and hands to the customer when
 * counterfeit notes are seized at the counter. The customer keeps it as
 * proof that:
 * <ul>
 *   <li>Specific notes (denomination + count) were impounded by the bank.</li>
 *   <li>The detection has a permanent register reference for traceability.</li>
 *   <li>An FIR is being filed (when count >= 5 per RBI threshold).</li>
 *   <li>The notes will be remitted to the currency chest.</li>
 * </ul>
 *
 * <p>The slip is also printed in duplicate -- one copy goes with the
 * impounded notes to the chest dispatch envelope, one stays in branch
 * records.
 *
 * <p>Field grouping mirrors the printable layout (top: register ref, branch,
 * timestamp; middle: depositor identification; bottom: denomination block
 * + FIR notice). Per RBI: the printed slip MUST carry the {@code registerRef}
 * prominently and the branch code + date in human-readable form.
 */
public record FicnAcknowledgementResponse(

        /** Permanent reference: FICN/{branchCode}/{YYYYMMDD}/{seq}. */
        String registerRef,

        /** Originating cash-deposit txn ref or idempotency key. */
        String originatingTxnRef,

        String branchCode,
        String branchName,
        LocalDate detectionDate,
        LocalDateTime detectionTimestamp,
        String detectedByTeller,

        /** PMLA Rule 9: who tendered the cash. */
        String depositorName,
        String depositorIdType,
        String depositorIdNumber,
        String depositorMobile,

        /**
         * Per-denomination breakdown of impounded counterfeits. Most FICN
         * incidents involve a single denomination but the response shape
         * accepts multiple rows so a future "all denominations on one slip"
         * change doesn't break the BFF receipt component.
         */
        List<FicnDenominationLine> impoundedDenominations,

        /** Total face value of impounded notes. */
        BigDecimal totalFaceValue,

        /**
         * Whether RBI mandates an FIR for this incident (count >= 5 per
         * RBI Master Direction). The BFF prints "FIR mandatory -- branch
         * will file with local police" prominently when true.
         */
        boolean firRequired,

        /**
         * Currency-chest dispatch status at the time of receipt printing.
         * Always {@code PENDING} on the customer's slip; the value is here
         * so the same DTO is reused for the supervisor's view of the
         * register entry where the status can be DISPATCHED / REMITTED.
         */
        String chestDispatchStatus,

        String remarks
) {

    /**
     * Per-denomination line on the FICN slip. Same shape as the cash-deposit
     * denomination line for receipt-template symmetry.
     */
    public record FicnDenominationLine(
            IndianCurrencyDenomination denomination,
            long counterfeitCount,
            BigDecimal totalFaceValue
    ) {
    }
}
