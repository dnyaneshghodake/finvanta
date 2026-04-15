package com.finvanta.service.impl;

import com.finvanta.service.DocumentStorageService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * CBS Database Document Storage — BLOB stored in customer_documents table.
 *
 * Per Finacle DOC_MASTER default configuration:
 * - Document file content stored as BLOB in the same table as metadata
 * - Simplest deployment — no external storage infrastructure required
 * - Suitable for banks with < 50K customers / < 100K documents
 * - DB-level TDE (Transparent Data Encryption) provides encryption at rest
 *
 * Per RBI IT Governance Direction 2023:
 * - BLOB data is encrypted via DB-level TDE or application-level encryption
 * - Backup includes document data (single backup strategy)
 * - No external dependency — reduces operational risk
 *
 * Activated when: finvanta.document.storage.type=DATABASE (default)
 * or when the property is not set at all (matchIfMissing=true).
 *
 * In this mode:
 * - store() returns "BLOB" (sentinel value — data is in the entity's fileData column)
 * - retrieve() returns the entity's BLOB data directly (ignores storagePath)
 * - delete() is a no-op (BLOB is deleted with the entity row via JPA cascade)
 */
@Service
@ConditionalOnProperty(
        name = "finvanta.document.storage.type",
        havingValue = "DATABASE",
        matchIfMissing = true)
public class DatabaseDocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDocumentStorageService.class);

    /** Sentinel value indicating file data is stored in the entity's BLOB column. */
    public static final String BLOB_SENTINEL = "BLOB";

    public DatabaseDocumentStorageService() {
        log.info("CBS Document Storage: DATABASE mode active (BLOB in customer_documents table). "
                + "Per Finacle DOC_MASTER: suitable for < 50K customers.");
    }

    @Override
    public String store(String tenantId, Long customerId, Long docId, String fileName, byte[] fileData) {
        // Data is stored in the entity's @Lob fileData column by JPA.
        // No external storage operation needed — return sentinel.
        log.debug("Document stored in DB BLOB: tenant={}, customer={}, doc={}, file={}, size={}",
                tenantId, customerId, docId, fileName, fileData != null ? fileData.length : 0);
        return BLOB_SENTINEL;
    }

    @Override
    public byte[] retrieve(String storagePath, byte[] blobData) {
        // In DATABASE mode, file content is in the entity's BLOB column.
        // The blobData parameter IS the file content — return it directly.
        return blobData;
    }

    @Override
    public void delete(String storagePath) {
        // No-op: BLOB data is deleted when the entity row is deleted by JPA.
        // Per CBS: documents are never hard-deleted (soft-delete via status change).
    }

    @Override
    public String getStorageType() {
        return "DATABASE";
    }
}
