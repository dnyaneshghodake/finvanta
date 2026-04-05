package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import org.springframework.stereotype.Component;

/**
 * Asset classification per RBI IRAC norms and Early Warning Framework.
 *
 * DPD-based classification (RBI Master Circular):
 *   0 DPD         → ACTIVE (Standard)
 *   1-30 DPD      → SMA-0 (Special Mention Account)
 *   31-60 DPD     → SMA-1
 *   61-90 DPD     → SMA-2
 *   91-365 DPD    → NPA Sub-Standard (12 months)
 *   366-1095 DPD  → NPA Doubtful (12-36 months)
 *   >1095 DPD     → NPA Loss (>36 months)
 *
 * Key rules:
 * - SMA can auto-upgrade (worsen) and auto-downgrade (improve) based on DPD
 * - NPA can only worsen, never auto-downgrade — requires explicit arrears clearance
 * - Income recognition must stop when account becomes NPA
 */
@Component
public class NpaClassificationRule {

    private static final int SMA_0_THRESHOLD = 1;
    private static final int SMA_1_THRESHOLD = 31;
    private static final int SMA_2_THRESHOLD = 61;
    private static final int NPA_THRESHOLD_DAYS = 91;
    private static final int SUBSTANDARD_MAX_DAYS = 365;
    private static final int DOUBTFUL_MAX_DAYS = 1095;

    /**
     * Classifies loan status per RBI IRAC norms and Early Warning Framework.
     *
     * Classification rules:
     * - SMA status is fluid (can improve with payment, worsen with DPD increase)
     * - NPA status is sticky (can only worsen; improvement requires explicit arrears clearance)
     * - RESTRUCTURED accounts: retain RESTRUCTURED status unless DPD crosses NPA threshold,
     *   at which point they transition to NPA per RBI CDR norms. Restructured accounts
     *   that become NPA cannot auto-revert to RESTRUCTURED.
     * - Terminal states (CLOSED, WRITTEN_OFF) are never reclassified.
     */
    public LoanStatus classify(LoanAccount account) {
        int dpd = account.getDaysPastDue();
        LoanStatus currentStatus = account.getStatus();

        // Terminal states — no reclassification
        if (currentStatus.isTerminal()) {
            return currentStatus;
        }

        LoanStatus dpdBasedStatus = classifyByDpd(dpd);

        // RBI CDR: Restructured accounts stay RESTRUCTURED unless they cross NPA threshold.
        // Once an NPA, a restructured account follows normal NPA sticky rules.
        if (currentStatus == LoanStatus.RESTRUCTURED && !dpdBasedStatus.isNpa()) {
            return LoanStatus.RESTRUCTURED;
        }

        // RBI IRAC: NPA can only worsen, never auto-downgrade
        if (currentStatus.isNpa() && !dpdBasedStatus.isNpa()) {
            return currentStatus;
        }
        if (currentStatus.isNpa() && dpdBasedStatus.isNpa()
                && getSeverity(dpdBasedStatus) < getSeverity(currentStatus)) {
            return currentStatus;
        }

        return dpdBasedStatus;
    }

    /**
     * Pure DPD-based classification without sticky NPA logic.
     * Used for initial classification and reporting.
     */
    public LoanStatus classifyByDpd(int dpd) {
        if (dpd < SMA_0_THRESHOLD) {
            return LoanStatus.ACTIVE;
        } else if (dpd < SMA_1_THRESHOLD) {
            return LoanStatus.SMA_0;
        } else if (dpd < SMA_2_THRESHOLD) {
            return LoanStatus.SMA_1;
        } else if (dpd < NPA_THRESHOLD_DAYS) {
            return LoanStatus.SMA_2;
        } else if (dpd <= SUBSTANDARD_MAX_DAYS) {
            return LoanStatus.NPA_SUBSTANDARD;
        } else if (dpd <= DOUBTFUL_MAX_DAYS) {
            return LoanStatus.NPA_DOUBTFUL;
        } else {
            return LoanStatus.NPA_LOSS;
        }
    }

    private int getSeverity(LoanStatus status) {
        return switch (status) {
            case ACTIVE -> 0;
            case SMA_0 -> 1;
            case SMA_1 -> 2;
            case SMA_2 -> 3;
            case NPA_SUBSTANDARD -> 4;
            case NPA_DOUBTFUL -> 5;
            case NPA_LOSS -> 6;
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
