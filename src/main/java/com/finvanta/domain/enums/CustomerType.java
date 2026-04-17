package com.finvanta.domain.enums;

/**
 * CBS Customer Type per RBI KYC Direction / CERSAI CKYC Specification v2.0.
 *
 * <p>Closed enumeration — determines:
 * <ul>
 *   <li>KYC document requirements (OVD list varies by type)</li>
 *   <li>CKYC account type mapping (INDIVIDUAL vs NON_INDIVIDUAL for CERSAI upload)</li>
 *   <li>Regulatory treatment (FEMA for NRI, guardian KYC for MINOR, etc.)</li>
 * </ul>
 *
 * <p>DB storage: VARCHAR via {@code @Enumerated(EnumType.STRING)}.
 */
public enum CustomerType {

    INDIVIDUAL("Individual", true),
    JOINT("Joint", true),
    HUF("HUF", false),
    PARTNERSHIP("Partnership", false),
    COMPANY("Company", false),
    TRUST("Trust", false),
    NRI("NRI", true),
    MINOR("Minor", true),
    GOVERNMENT("Government", false);

    private final String displayName;
    /** Whether this type maps to CERSAI INDIVIDUAL account type. */
    private final boolean ckycIndividual;

    CustomerType(String displayName, boolean ckycIndividual) {
        this.displayName = displayName;
        this.ckycIndividual = ckycIndividual;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the CKYC account type for CERSAI upload.
     * Per CERSAI spec: INDIVIDUAL/JOINT/MINOR/NRI → "INDIVIDUAL"; rest → "NON_INDIVIDUAL".
     */
    public String getCkycAccountType() {
        return ckycIndividual ? "INDIVIDUAL" : "NON_INDIVIDUAL";
    }

    /**
     * Safe valueOf that returns null instead of throwing IllegalArgumentException.
     */
    public static CustomerType fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
