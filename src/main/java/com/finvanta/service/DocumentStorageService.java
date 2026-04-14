package com.finvanta.service;

/**
 * CBS Document Storage Service per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE.
 *
 * Abstraction layer for document file storage that supports multiple backends:
 *   - DATABASE: BLOB stored directly in the customer_documents table (default)
 *   - FILESYSTEM: Files stored on local filesystem / NAS mount with path reference in DB
 *
 * Per RBI IT Governance Direction 2023:
 * - Documents must be encrypted at rest regardless of storage backend
 * - Access to documents must be logged in audit trail
 * - Documents must be retained for minimum 5 years after account closure
 *
 * Per Finacle DOC_MASTER architecture:
 * - DOC_MASTER table stores metadata (type, status, verification, customer link)
 * - File content stored either inline (BLOB) or external (DMS path reference)
 * - Storage backend is configurable per deployment without code changes
 * - Migration between backends is supported via batch re-storage utility
 *
 * Per Temenos IM.DOCUMENT.IMAGE:
 * - Document images stored in configurable IMAGE.STORE
 * - Supports DB, filesystem, and external DMS (Documentum, OpenText)
 * - Storage path includes tenant isolation: {base}/{tenantId}/{customerId}/{docId}
 *
 * Configuration (application.properties):
 *   finvanta.document.storage.type=DATABASE          (default — BLOB in DB)
 *   finvanta.document.storage.type=FILESYSTEM        (external filesystem)
 *   finvanta.document.storage.filesystem.base-path=  (required for FILESYSTEM)
 *
 * The controller/service layer calls store() and retrieve() without knowing
 * which backend is active — classic Strategy Pattern per GoF.
 *
 * IMPORTANT: When switching from DATABASE to FILESYSTEM in production,
 * existing BLOB data must be migrated via a batch job. New uploads go to
 * the configured backend; downloads check both (DB BLOB first, then path).
 */
public interface DocumentStorageService {

    /**
     * Stores document file content and returns a storage reference.
     *
     * For DATABASE backend: stores bytes in entity's fileData BLOB, returns "BLOB".
     * For FILESYSTEM backend: writes file to disk, returns the storage path.
     *
     * @param tenantId   Tenant identifier (for path isolation)
     * @param customerId Customer ID (for path isolation)
     * @param docId      Document entity ID (for unique filename)
     * @param fileName   Original sanitized filename
     * @param fileData   Raw file bytes
     * @return Storage reference — "BLOB" for database, filesystem path for external
     */
    String store(String tenantId, Long customerId, Long docId, String fileName, byte[] fileData);

    /**
     * Retrieves document file content by storage reference.
     *
     * For DATABASE backend: returns the entity's fileData BLOB directly.
     * For FILESYSTEM backend: reads file from disk at the stored path.
     *
     * @param storagePath Storage reference returned by store()
     * @param blobData    The entity's fileData BLOB (used by DATABASE backend)
     * @return Raw file bytes
     */
    byte[] retrieve(String storagePath, byte[] blobData);

    /**
     * Deletes document file content from storage.
     * Called when a document entity is deleted (if ever — CBS prefers soft-delete).
     *
     * For DATABASE backend: no-op (BLOB is deleted with the entity row).
     * For FILESYSTEM backend: deletes the file from disk.
     *
     * @param storagePath Storage reference returned by store()
     */
    void delete(String storagePath);

    /**
     * Returns the active storage type name for logging/audit.
     * @return "DATABASE" or "FILESYSTEM"
     */
    String getStorageType();
}
