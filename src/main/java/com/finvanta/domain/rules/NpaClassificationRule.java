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

    public LoanStatus classify(LoanAccount account) {
        int dpd = account.getDaysPastDue();

        if (dpd < NPA_THRESHOLD_DAYS) {
            return LoanStatus.ACTIVE;
        } else if (dpd <= SUBSTANDARD_MAX_DAYS) {
            return LoanStatus.NPA_SUBSTANDARD;
        } else if (dpd <= DOUBTFUL_MAX_DAYS) {
            return LoanStatus.NPA_DOUBTFUL;
        } else {
            return LoanStatus.NPA_LOSS;
        }
    }

    public boolean isNpa(int daysPastDue) {
        return daysPastDue >= NPA_THRESHOLD_DAYS;
    }

    public int getNpaThresholdDays() {
        return NPA_THRESHOLD_DAYS;
    }
}
