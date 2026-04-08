package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Loan Document per Finacle DOCMAS / Temenos AA.DOCUMENT.
 *
 * Tracks documents submitted for a loan application. Each product type has a
 * mandatory document checklist (defined in product_document_checklist table or
 * configured per product). Documents go through a verification workflow:
 *
 *   PENDING -> VERIFIED -> REJECTED
 *
 * Per RBI KYC/AML norms and Fair Practices Code:
 * - Identity proof (PAN/Aadhaar) is mandatory for all loan products
 * - Income proof is mandatory for unsecured loans above threshold
 * - Property documents are mandatory for LAP/Home Loan
 * - Gold appraisal certificate is mandatory for Gold Loan
 * - All documents must be verified by CHECKER before approval
 *
 * Document storage: file_path references the storage location (filesystem or
 * object storage). The actual file content is NOT stored in the database.
 * For production: use AWS S3, Azure Blob, or MinIO for document storage.
 */
@Entity
@Table(
        name = "loan_documents",
        indexes = {
            @Index(name = "idx_loandoc_app", columnList = "tenant_id, loan_application_id"),
            @Index(name = "idx_loandoc_type", columnList = "tenant_id, document_type")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    /** Document category per CBS checklist */
    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    /** Human-readable document name */
    @Column(name = "document_name", nullable = false, length = 200)
    private String documentName;

    /** Original uploaded file name */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** Storage path (filesystem, S3 key, or blob reference) */
    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    /** File size in bytes */
    @Column(name = "file_size")
    private Long fileSize;

    /** MIME type: application/pdf, image/jpeg, etc. */
    @Column(name = "content_type", length = 100)
    private String contentType;

    // --- Verification Workflow ---

    /** PENDING, VERIFIED, REJECTED */
    @Column(name = "verification_status", nullable = false, length = 20)
    private String verificationStatus = "PENDING";

    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    @Column(name = "verified_date")
    private LocalDate verifiedDate;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // --- Document Validity ---

    /** Expiry date for time-bound documents (insurance, valuation reports) */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Whether this document is mandatory per product checklist */
    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory = false;

    @Column(name = "remarks", length = 500)
    private String remarks;

    public boolean isExpired() {
        return expiryDate != null && LocalDate.now().isAfter(expiryDate);
    }

    public boolean isVerified() {
        return "VERIFIED".equals(verificationStatus);
    }

    public boolean isRejected() {
        return "REJECTED".equals(verificationStatus);
    }
}
