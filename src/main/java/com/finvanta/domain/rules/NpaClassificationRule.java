package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import org.springframework.stereotype.Component;

/**
 * NPA classification as per RBI IRAC norms:
 * - Standard: 0-89 days past due
 * - Sub-Standard: 90-365 days (12 months)
 * - Doubtful: 366-1095 days (12-36 months)
 * - Loss: >1095 days (>36 months)
 */
@Component
public class NpaClassificationRule {

    private static final int NPA_THRESHOLD_DAYS = 90;
    private static final int SUBSTANDARD_MAX_DAYS = 365;
    private static final int DOUBTFUL_MAX_DAYS = 1095;

    /**
     * Classifies loan NPA status per RBI IRAC norms.
     * NPA status can only be upgraded (worsened), never downgraded.
     * Per RBI guidelines, an NPA account cannot be reclassified as standard
     * merely because DPD drops — all arrears must be cleared first.
     * Downgrade (upgrade to standard) must be handled explicitly via
     * a separate arrears-clearance workflow, not by DPD alone.
     */
    public LoanStatus classify(LoanAccount account) {
        int dpd = account.getDaysPastDue();
        LoanStatus currentStatus = account.getStatus();

        LoanStatus dpdBasedStatus;
        if (dpd < NPA_THRESHOLD_DAYS) {
            dpdBasedStatus = LoanStatus.ACTIVE;
        } else if (dpd <= SUBSTANDARD_MAX_DAYS) {
            dpdBasedStatus = LoanStatus.NPA_SUBSTANDARD;
        } else if (dpd <= DOUBTFUL_MAX_DAYS) {
            dpdBasedStatus = LoanStatus.NPA_DOUBTFUL;
        } else {
            dpdBasedStatus = LoanStatus.NPA_LOSS;
        }

        // RBI IRAC: NPA can only worsen, never auto-downgrade
        // Once NPA, only explicit upgrade via arrears clearance is allowed
        if (currentStatus.isNpa() && !dpdBasedStatus.isNpa()) {
            return currentStatus; // Retain current NPA status
        }
        if (currentStatus.isNpa() && dpdBasedStatus.isNpa()
                && getNpaSeverity(dpdBasedStatus) < getNpaSeverity(currentStatus)) {
            return currentStatus; // Cannot improve NPA tier automatically
        }

        return dpdBasedStatus;
    }

    private int getNpaSeverity(LoanStatus status) {
        return switch (status) {
            case ACTIVE -> 0;
            case NPA_SUBSTANDARD -> 1;
            case NPA_DOUBTFUL -> 2;
            case NPA_LOSS -> 3;
            default -> -1;
        };
    }

    public boolean isNpa(int daysPastDue) {
        return daysPastDue >= NPA_THRESHOLD_DAYS;
    }

    public int getNpaThresholdDays() {
        return NPA_THRESHOLD_DAYS;
    }
}
