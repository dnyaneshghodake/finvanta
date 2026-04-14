package com.finvanta.domain.entity;

import com.finvanta.domain.enums.DocumentType;
import com.finvanta.domain.enums.DocumentVerificationStatus;

import jakarta.persistence.*;

import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Customer Document Entity per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE.
 *
 * Stores scanned copies of KYC documents (PAN, Aadhaar, Passport, Address Proof, Photo)
 * linked to a Customer CIF. Per RBI KYC Master Direction 2016 Section 16:
 * - Banks must maintain copies of officially valid documents (OVDs) used for KYC
 * - Documents must be retained for minimum 5 years after account closure
 * - Per RBI IT Governance Direction 2023: documents encrypted at rest
 *
 * Document Lifecycle:
 *   UPLOADED → VERIFIED (by CHECKER) → EXPIRED (past validity date)
 *   UPLOADED → REJECTED (invalid/unclear document)
 *
 * Per Finacle DOC_MASTER:
 * - Multiple documents per customer (PAN card front, Aadhaar front/back, photo, etc.)
 * - Each document has type, file data, upload metadata, and verification status
 * - Documents are immutable once verified — new version uploaded for updates
 * - File stored as BLOB in DB (for simplicity) or external storage path
 *
 * Per UIDAI Aadhaar Act 2016 Section 29:
 * - Aadhaar document images must be stored encrypted
 * - Access must be logged in audit trail
 *
 * Supported Document Types (per RBI KYC Direction — OVD list):
 *   PAN_CARD, AADHAAR_FRONT, AADHAAR_BACK, PASSPORT, VOTER_ID,
 *   DRIVING_LICENSE, UTILITY_BILL, BANK_STATEMENT, RENT_AGREEMENT,
 *   PHOTO (passport-size), SIGNATURE, FORM_60 (for non-PAN holders),
 *   ITR (Income Tax Return), SALARY_SLIP, COMPANY_REG, TRUST_DEED
 */
@Entity
@Table(
        name = "customer_documents",
        indexes = {
            @Index(name = "idx_custdoc_tenant_customer", columnList = "tenant_id, customer_id"),
            @Index(name = "idx_custdoc_tenant_type", columnList = "tenant_id, customer_id, document_type"),
            @Index(name = "idx_custdoc_status", columnList = "tenant_id, verification_status")
        })
@Getter
@Setter
@NoArgsConstructor
public class CustomerDocument extends BaseEntity {

    /** Customer this document belongs to */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /**
     * Document type per RBI KYC Direction OVD list.
     * Per Finacle DOC_MASTER: stored as VARCHAR via @Enumerated(EnumType.STRING).
     * Compile-time safe — prevents invalid document types at code level.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    /** Original filename as uploaded by the user */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** MIME content type (application/pdf, image/jpeg, image/png) */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** File size in bytes — enforced max 5MB per document per RBI IT Governance */
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /**
     * Document file content stored as BLOB (DATABASE storage mode).
     * Per RBI IT Governance Direction 2023: stored encrypted via DB-level TDE
     * or application-level encryption for sensitive documents (Aadhaar).
     *
     * When finvanta.document.storage.type=FILESYSTEM, this column is NULL
     * for new uploads (file content is on external filesystem). The storagePath
     * column contains the filesystem reference instead.
     *
     * When finvanta.document.storage.type=DATABASE (default), this column
     * contains the full file content and storagePath is "BLOB".
     *
     * Per Finacle DOC_MASTER: supports both inline BLOB and external DMS reference.
     */
    @Lob
    @Column(name = "file_data")
    private byte[] fileData;

    /**
     * External storage path reference per Finacle DOC_MASTER / Temenos IMAGE.REF.
     *
     * Values:
     *   "BLOB"                    — file content is in the fileData BLOB column (DATABASE mode)
     *   "{tenantId}/documents/..." — relative path on external filesystem (FILESYSTEM mode)
     *   null                      — legacy record (pre-storage-abstraction, treated as BLOB)
     *
     * Per Temenos IM.DOCUMENT.IMAGE: IMAGE.REF stores the external storage reference.
     * The DocumentStorageService uses this field to locate the file content.
     */
    @Column(name = "storage_path", length = 500)
    private String storagePath;

    /**
     * Document number/reference on the document itself.
     * E.g., PAN number on PAN card, passport number on passport.
     * Null for documents without a reference number (photo, signature).
     */
    @Column(name = "document_number", length = 50)
    private String documentNumber;

    /** Issuing authority (e.g., "UIDAI", "Income Tax Dept", "RTO Mumbai") */
    @Column(name = "issuing_authority", length = 200)
    private String issuingAuthority;

    /** Date of issue of the document */
    @Column(name = "issue_date")
    private LocalDate issueDate;

    /** Expiry date of the document (null for non-expiring like PAN, Aadhaar) */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * Verification status per CBS document lifecycle.
     * Values: UPLOADED, VERIFIED, REJECTED, EXPIRED
     */
    @Column(name = "verification_status", nullable = false, length = 20)
    private String verificationStatus = "UPLOADED";

    /** Who verified/rejected the document (CHECKER username) */
    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    /** Date when document was verified/rejected */
    @Column(name = "verified_date")
    private LocalDate verifiedDate;

    /** Rejection reason (mandatory when status = REJECTED) */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** Remarks/notes about the document */
    @Column(name = "remarks", length = 500)
    private String remarks;

    // === Helpers ===

    public boolean isVerified() {
        return "VERIFIED".equals(verificationStatus);
    }

    public boolean isRejected() {
        return "REJECTED".equals(verificationStatus);
    }

    public boolean isExpired() {
        if (expiryDate == null) return false;
        return java.time.LocalDate.now().isAfter(expiryDate);
    }

    /** Returns true if this is an identity document (PAN, Aadhaar, Passport, etc.) */
    public boolean isIdentityDocument() {
        return "PAN_CARD".equals(documentType) || "AADHAAR_FRONT".equals(documentType)
                || "AADHAAR_BACK".equals(documentType) || "PASSPORT".equals(documentType)
                || "VOTER_ID".equals(documentType) || "DRIVING_LICENSE".equals(documentType);
    }

    /** Returns true if this is an address proof document */
    public boolean isAddressProof() {
        return "UTILITY_BILL".equals(documentType) || "BANK_STATEMENT".equals(documentType)
                || "RENT_AGREEMENT".equals(documentType) || "PASSPORT".equals(documentType)
                || "VOTER_ID".equals(documentType) || "DRIVING_LICENSE".equals(documentType)
                || "AADHAAR_FRONT".equals(documentType);
    }
}
