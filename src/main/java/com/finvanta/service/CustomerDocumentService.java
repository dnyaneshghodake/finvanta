package com.finvanta.service;

import com.finvanta.domain.entity.CustomerDocument;

import java.util.List;

/**
 * CBS Customer Document Service per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE.
 *
 * Central service layer for all KYC document operations.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller → Service → Repository
 *   All document business logic, validation, storage, and audit reside HERE.
 *   Controller only handles HTTP request/response mapping.
 *
 * Per RBI KYC Master Direction 2016 Section 16:
 * - Banks must maintain copies of officially valid documents (OVDs) used for KYC
 * - Documents must be retained for minimum 5 years after account closure
 * - Document verification follows maker-checker workflow
 * - Every document state change is audited
 *
 * Per RBI IT Governance Direction 2023:
 * - Document content validated by magic bytes (not just Content-Type header)
 * - Filenames sanitized to prevent path traversal / header injection
 * - Storage backend configurable (DATABASE BLOB or FILESYSTEM)
 */
public interface CustomerDocumentService {

    /**
     * Upload a KYC document for a customer.
     * Validates: file size, format, magic bytes, document type, filename.
     * Stores via configurable DocumentStorageService (DATABASE or FILESYSTEM).
     * Audits the upload event.
     *
     * @param customerId   Customer ID (branch access enforced)
     * @param documentType Document type from OVD list (server-validated)
     * @param fileName     Original filename (will be sanitized)
     * @param contentType  MIME content type
     * @param fileSize     File size in bytes
     * @param fileData     Raw file bytes
     * @param documentNumber Optional document reference number
     * @param remarks      Optional remarks
     * @return Created document entity
     */
    CustomerDocument uploadDocument(Long customerId, String documentType,
            String fileName, String contentType, long fileSize, byte[] fileData,
            String documentNumber, String remarks);

    /**
     * Download document content by document ID.
     * Enforces branch access on the document's customer.
     * Retrieves via configurable DocumentStorageService.
     *
     * @param docId Document ID
     * @return Document entity (caller reads fileData or uses storage service)
     */
    CustomerDocument getDocument(Long docId);

    /**
     * Retrieve file content for a document via the configured storage backend.
     *
     * @param doc Document entity
     * @return Raw file bytes
     */
    byte[] retrieveFileContent(CustomerDocument doc);

    /**
     * Verify or reject a document (CHECKER/ADMIN only).
     * Enforces: branch access, maker-checker self-verify prevention, immutability.
     * Uses CBS business date for verification date.
     *
     * @param docId           Document ID
     * @param action          "VERIFY" or "REJECT"
     * @param rejectionReason Mandatory if action is "REJECT"
     * @return Updated document entity
     */
    CustomerDocument verifyDocument(Long docId, String action, String rejectionReason);

    /**
     * List all documents for a customer (ordered by upload date desc).
     *
     * @param customerId Customer ID
     * @return List of documents
     */
    List<CustomerDocument> getDocumentsForCustomer(Long customerId);
}
