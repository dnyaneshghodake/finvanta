package com.finvanta.cbs.modules.teller.exception;

import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse;
import com.finvanta.util.BusinessException;

/**
 * CBS FICN-detected control exception per RBI Master Direction on
 * Counterfeit Notes.
 *
 * <p>Thrown by {@code TellerServiceImpl.cashDeposit(...)} when the
 * denomination breakdown contains counterfeit-flagged notes AFTER the
 * register entry has been written (so the customer always leaves with a
 * permanent FICN reference, regardless of how the API response is consumed).
 *
 * <p>Carries the {@link FicnAcknowledgementResponse} so the
 * {@code CbsApiExceptionHandler} (and the JSP controller) can include the
 * acknowledgement slip in the error response body / flash attribute. The
 * customer-facing experience is therefore:
 * <ul>
 *   <li>Deposit is rejected (the bank does not credit the customer for
 *       the counterfeit portion -- per Option B, the genuine portion is
 *       also rejected and must be re-tendered in a fresh transaction).</li>
 *   <li>HTTP 422 with body containing the FICN acknowledgement payload.</li>
 *   <li>BFF / JSP renders the printable FICN slip from the payload.</li>
 *   <li>The {@code CounterfeitNoteRegister} row is committed as part of the
 *       same transaction -- so the slip the customer holds is permanent
 *       and verifiable against the bank's records.</li>
 * </ul>
 *
 * <p>Extends {@link BusinessException} so it flows through the existing
 * exception-handling pipeline (correlation ID enrichment, audit, metrics)
 * without special-case wiring. The error code is the existing
 * {@link CbsErrorCodes#TELLER_COUNTERFEIT_DETECTED} so all severity /
 * status / remediation mappings continue to apply.
 */
public class FicnDetectedException extends BusinessException {

    /** Printable FICN acknowledgement carried as the exception payload. */
    private final FicnAcknowledgementResponse acknowledgement;

    public FicnDetectedException(FicnAcknowledgementResponse acknowledgement) {
        super(CbsErrorCodes.TELLER_COUNTERFEIT_DETECTED,
                "Counterfeit notes detected and impounded. FICN register: "
                        + acknowledgement.registerRef()
                        + (acknowledgement.firRequired()
                                ? " | FIR mandatory per RBI (count >= 5)"
                                : ""));
        this.acknowledgement = acknowledgement;
    }

    public FicnAcknowledgementResponse getAcknowledgement() {
        return acknowledgement;
    }
}
