package com.finvanta.service;

import com.finvanta.domain.entity.LoanDocument;
import com.finvanta.domain.enums.DocumentType;

import java.util.List;

/**
 * CBS Loan Document Service — manages LoanDocument CRUD per Finacle DOCUPLOAD / Temenos AA.DOCUMENT.
 *
 * All mutations MUST be performed through this service (not directly in controllers).
 * The service enforces:
 * - Multi-tenant isolation (tenantId from TenantContext)
 * - Transactional atomicity (@Transactional on all write operations)
 * - Document validation (type, file size, format)
 */
public interface LoanDocumentService {

    /**
     * Upload a new loan document.
     *
     * @param loanApplicationId The loan application ID
     * @param documentType The document type (e.g., ID_PROOF, ADDRESS_PROOF, INCOME_PROOF)
     * @param fileName Original filename
     * @param fileContent File content as byte array
     * @param contentType MIME type
     * @param remarks Optional remarks
     * @return The created LoanDocument
     */
    LoanDocument uploadDocument(
            Long loanApplicationId,
            DocumentType documentType,
            String fileName,
            byte[] fileContent,
            String contentType,
            String remarks);

    /**
     * Update document remarks.
     *
     * @param documentId Document ID
     * @param remarks New remarks
     * @return Updated document
     */
    LoanDocument updateDocumentRemarks(Long documentId, String remarks);

    /**
     * Delete a document.
     *
     * @param documentId Document ID to delete
     */
    void deleteDocument(Long documentId);

    /**
     * Verify a document (mark as VERIFIED).
     *
     * @param documentId Document ID
     */
    void verifyDocument(Long documentId);

    /**
     * Reject a document (mark as REJECTED).
     *
     * @param documentId Document ID
     * @param rejectionReason Reason for rejection
     */
    void rejectDocument(Long documentId, String rejectionReason);

    /**
     * List documents for a loan application.
     *
     * @param loanApplicationId Loan application ID
     * @return List of documents
     */
    List<LoanDocument> listDocuments(Long loanApplicationId);

    /**
     * Get a document by ID.
     *
     * @param documentId Document ID
     * @return The document
     */
    LoanDocument getDocument(Long documentId);
}