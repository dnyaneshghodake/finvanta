package com.finvanta.cbs.modules.teller.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * CBS Teller Cash Deposit Request per CBS TELLER_CASH_DEP standard.
 *
 * <p>Captures over-the-counter cash deposit input from the branch teller channel.
 * Decoupled from the JPA entity per Tier-1 DTO isolation rules (CBS Tier-1
 * Architecture Audit, finding C3 -- entity exposure).
 *
 * <p>Validation contract:
 * <ul>
 *   <li>{@code accountNumber} -- non-blank target deposit account</li>
 *   <li>{@code amount} -- positive (zero rejected)</li>
 *   <li>{@code denominations} -- non-empty grid; the SUM of
 *       {@code denomination.value() * unitCount} MUST equal {@code amount}.
 *       Enforced by {@code DenominationValidator} BEFORE any GL or till mutation.</li>
 *   <li>{@code idempotencyKey} -- mandatory. JSP mints a server-side UUID per
 *       page render (per the F1 fix in this branch); the v2 REST API requires
 *       BFF-supplied UUIDs so retry semantics are deterministic.</li>
 *   <li>{@code depositorName} -- mandatory under PMLA 2002 §12 for any cash
 *       deposit by a non-customer (third-party deposit). For self-deposits, the
 *       BFF / JSP can default this to the account holder name.</li>
 * </ul>
 *
 * <p>The {@code panNumber} field is optional but MUST be supplied for cash
 * deposits at or above the CTR threshold (INR 50,000 by default per RBI
 * Operational Risk Guidelines and PMLA Rule 9). The service layer rejects the
 * request when threshold is breached and PAN is missing.
 */
public record CashDepositRequest(

        @NotBlank(message = "Target account number is required")
        @Size(max = 40, message = "Account number too long")
        String accountNumber,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        BigDecimal amount,

        @NotEmpty(message = "Denomination breakdown is required for teller cash deposit")
        @Valid
        List<DenominationEntry> denominations,

        @NotBlank(message = "Idempotency key is required for cash deposit")
        @Size(max = 100, message = "Idempotency key too long")
        String idempotencyKey,

        /**
         * Name of the person physically tendering the cash. Mandatory per PMLA
         * 2002 §12 for third-party deposits. Stored for AML / KYC trail.
         */
        @NotBlank(message = "Depositor name is required per PMLA 2002")
        @Size(max = 200, message = "Depositor name too long")
        String depositorName,

        /**
         * Mobile number of the depositor. Used for SMS confirmation receipts
         * and AML matching against KYC records. Not validated for format here;
         * the service layer applies the {@code [6-9]\d{9}} pattern when present.
         */
        @Size(max = 20, message = "Depositor mobile too long")
        String depositorMobile,

        /**
         * Depositor PAN. MANDATORY for cash deposits >= CTR threshold per RBI
         * Operational Risk Guidelines and PMLA Rule 9. The service layer
         * enforces presence at threshold and rejects with COMP_CTR_THRESHOLD
         * otherwise.
         */
        @Size(max = 10, message = "PAN too long")
        String panNumber,

        @Size(max = 500, message = "Narration too long")
        String narration,

        /**
         * Form 60/61 reference for cash depositors who do not hold a PAN.
         * Required when {@code panNumber} is absent and {@code amount >=}
         * CTR threshold. RBI requires retention of the physical Form 60/61 in
         * the branch records; this field stores the form serial number.
         */
        @Size(max = 30, message = "Form 60/61 reference too long")
        String form60Reference
) {
}
