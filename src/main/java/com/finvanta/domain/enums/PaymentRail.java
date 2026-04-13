package com.finvanta.domain.enums;

/**
 * CBS Payment Rail Types per RBI Payment Systems Act 2007 / NPCI Framework.
 *
 * Each rail has distinct settlement characteristics:
 *   NEFT  — Deferred Net Settlement, half-hourly batches, no minimum amount
 *   RTGS  — Real-Time Gross Settlement, individual, minimum INR 2L
 *   IMPS  — Immediate Payment Service, real-time, 24x7, max INR 5L
 *   UPI   — Unified Payments Interface, real-time via NPCI switch, max INR 1L (default)
 *
 * Per Finacle CLG_MASTER / Temenos CLEARING.TYPE:
 * Each rail maps to its own suspense GL pair (inward + outward) for independent
 * reconciliation. The settlement mode determines whether transactions are netted
 * (NEFT) or settled individually (RTGS/IMPS/UPI).
 */
public enum PaymentRail {

    /** National Electronic Funds Transfer — half-hourly batch, deferred net settlement */
    NEFT("NEFT", SettlementMode.DEFERRED_NET, false),

    /** Real-Time Gross Settlement — individual settlement, minimum INR 2,00,000 */
    RTGS("RTGS", SettlementMode.REAL_TIME_GROSS, true),

    /** Immediate Payment Service — real-time, 24x7 via NPCI */
    IMPS("IMPS", SettlementMode.REAL_TIME_GROSS, true),

    /** Unified Payments Interface — real-time via NPCI UPI switch */
    UPI("UPI", SettlementMode.REAL_TIME_GROSS, true);

    private final String code;
    private final SettlementMode settlementMode;
    private final boolean realTime;

    PaymentRail(String code, SettlementMode settlementMode, boolean realTime) {
        this.code = code;
        this.settlementMode = settlementMode;
        this.realTime = realTime;
    }

    public String getCode() { return code; }
    public SettlementMode getSettlementMode() { return settlementMode; }
    public boolean isRealTime() { return realTime; }

    /** Whether this rail requires cycle-based netting (NEFT) vs individual settlement */
    public boolean requiresCycleNetting() { return settlementMode == SettlementMode.DEFERRED_NET; }

    /** Settlement mode classification per RBI Payment Systems */
    public enum SettlementMode {
        /** Each transaction settled individually (RTGS/IMPS/UPI) */
        REAL_TIME_GROSS,
        /** Transactions aggregated and net obligation settled per cycle (NEFT) */
        DEFERRED_NET
    }
}
