package com.finvanta.domain.enums;

/**
 * CBS Document Type Enum per RBI KYC Direction — Officially Valid Documents (OVD) list.
 *
 * Per RBI KYC Master Direction 2016 Section 3:
 * - Banks must accept only officially valid documents for KYC verification
 * - Document types must be from the prescribed OVD list
 *
 * Per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE:
 * - Document types are a closed enumeration — no arbitrary values allowed
 * - Each type maps to a specific RBI/CERSAI document category
 *
 * Compile-time safety: prevents invalid document types at code level.
 * DB storage: stored as VARCHAR via @Enumerated(EnumType.STRING).
 */
public enum DocumentType {

    // === Identity Documents (OVD per RBI KYC Direction) ===
    PAN_CARD("PAN Card", true, false),
    AADHAAR_FRONT("Aadhaar (Front)", true, true),
    AADHAAR_BACK("Aadhaar (Back)", false, false),
    PASSPORT("Passport", true, true),
    VOTER_ID("Voter ID", true, true),
    DRIVING_LICENSE("Driving License", true, true),

    // === Address Proof Documents ===
    UTILITY_BILL("Utility Bill", false, true),
    BANK_STATEMENT("Bank Statement", false, true),
    RENT_AGREEMENT("Rent Agreement", false, true),

    // === KYC Support Documents ===
    PHOTO("Passport Photo", false, false),
    SIGNATURE("Signature Specimen", false, false),
    FORM_60("Form 60 (Non-PAN)", false, false),
    ITR("Income Tax Return", false, false),
    SALARY_SLIP("Salary Slip", false, false),

    // === Non-Individual Entity Documents ===
    COMPANY_REG("Company Registration", false, false),
    TRUST_DEED("Trust Deed", false, false),

    // === Catch-all ===
    OTHER("Other Document", false, false);

    private final String displayName;
    private final boolean identityDocument;
    private final boolean addressProof;

    DocumentType(String displayName, boolean identityDocument, boolean addressProof) {
        this.displayName = displayName;
        this.identityDocument = identityDocument;
        this.addressProof = addressProof;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Returns true if this is an identity document (PAN, Aadhaar, Passport, etc.) */
    public boolean isIdentityDocument() {
        return identityDocument;
    }

    /** Returns true if this is an address proof document */
    public boolean isAddressProof() {
        return addressProof;
    }

    /**
     * Safe valueOf that returns null instead of throwing IllegalArgumentException.
     * Used for server-side validation of user input.
     */
    public static DocumentType fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
