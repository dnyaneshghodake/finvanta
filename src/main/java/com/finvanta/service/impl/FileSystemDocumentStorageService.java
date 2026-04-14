package com.finvanta.service.impl;

import com.finvanta.service.DocumentStorageService;
import com.finvanta.util.BusinessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * CBS Filesystem Document Storage per Finacle DOC_MASTER External DMS / Temenos IMAGE.STORE.
 *
 * Stores document files on local filesystem or NAS/SAN mount point.
 * Only a storage path reference is kept in the customer_documents table.
 *
 * Per Finacle DOC_MASTER (External Storage Mode):
 * - DOC_MASTER table stores metadata + external storage path reference
 * - File content stored on shared filesystem (NAS/SAN) or cloud mount (EFS/Azure Files)
 * - Suitable for banks with > 50K customers / high document volume
 * - Reduces DB size, improves backup/restore performance
 * - Files are organized by tenant → customer → document ID for isolation
 *
 * Per RBI IT Governance Direction 2023:
 * - Filesystem must be encrypted at rest (OS-level encryption: LUKS, BitLocker, EFS)
 * - Access permissions restricted to the application service account only
 * - Storage path must include tenant isolation to prevent cross-tenant access
 * - Backup strategy must include the external document store
 *
 * Per Temenos IM.DOCUMENT.IMAGE:
 * - IMAGE.STORE path is configurable per deployment
 * - Path structure: {base}/{tenantId}/{customerId}/{docId}_{filename}
 * - Temenos uses IMAGE.REF field in the entity to store the path
 *
 * Storage Path Structure:
 *   {basePath}/{tenantId}/documents/{customerId}/{docId}_{sanitizedFileName}
 *   Example: /opt/finvanta/docstore/TENANT001/documents/42/156_pan_card.pdf
 *
 * Configuration:
 *   finvanta.document.storage.type=FILESYSTEM
 *   finvanta.document.storage.filesystem.base-path=/opt/finvanta/docstore
 *
 * Activated when: finvanta.document.storage.type=FILESYSTEM
 */
@Service
@ConditionalOnProperty(
        name = "finvanta.document.storage.type",
        havingValue = "FILESYSTEM")
public class FileSystemDocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemDocumentStorageService.class);

    private final Path basePath;

    public FileSystemDocumentStorageService(
            @Value("${finvanta.document.storage.filesystem.base-path:}") String basePathStr) {

        if (basePathStr == null || basePathStr.isBlank()) {
            throw new IllegalStateException(
                    "CBS Document Storage: FILESYSTEM mode requires "
                            + "'finvanta.document.storage.filesystem.base-path' property. "
                            + "Set it to a writable directory path (e.g., /opt/finvanta/docstore).");
        }

        this.basePath = Paths.get(basePathStr).toAbsolutePath().normalize();

        // CBS: Validate base path exists and is writable at startup.
        // Per Finacle DOC_MASTER: storage path validation runs during SOD (Start-of-Day).
        // We validate at bean creation to fail-fast if storage is misconfigured.
        try {
            Files.createDirectories(this.basePath);
            if (!Files.isWritable(this.basePath)) {
                throw new IllegalStateException(
                        "CBS Document Storage: base path is not writable: " + this.basePath
                                + ". Check filesystem permissions for the application service account.");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "CBS Document Storage: cannot create base path: " + this.basePath, e);
        }

        log.info("CBS Document Storage: FILESYSTEM mode active. Base path: {}. "
                + "Per Finacle DOC_MASTER: suitable for > 50K customers.", this.basePath);
    }

    @Override
    public String store(String tenantId, Long customerId, Long docId, String fileName, byte[] fileData) {
        // CBS: Build tenant-isolated directory structure.
        // Per Temenos IMAGE.STORE: {base}/{tenantId}/documents/{customerId}/
        Path customerDir = basePath
                .resolve(sanitizePathComponent(tenantId))
                .resolve("documents")
                .resolve(String.valueOf(customerId));

        try {
            Files.createDirectories(customerDir);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_ERROR",
                    "Cannot create document directory: " + customerDir + ". " + e.getMessage());
        }

        // CBS: Filename format: {docId}_{sanitizedFileName}
        // The docId prefix ensures uniqueness even if the same filename is uploaded twice.
        String storedFileName = docId + "_" + (fileName != null ? fileName : "document");
        Path filePath = customerDir.resolve(storedFileName);

        // CBS Security: Validate resolved path is still within basePath (path traversal defense).
        // Even though fileName is sanitized by the controller, defense-in-depth applies.
        if (!filePath.normalize().startsWith(basePath)) {
            throw new BusinessException("STORAGE_PATH_VIOLATION",
                    "Resolved storage path escapes base directory. Possible path traversal attack.");
        }

        try {
            Files.write(filePath, fileData,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_WRITE_ERROR",
                    "Failed to write document to filesystem: " + e.getMessage());
        }

        // Return relative path from basePath for portability.
        // If basePath changes (mount point migration), relative paths still resolve.
        String relativePath = basePath.relativize(filePath).toString();

        log.debug("Document stored on filesystem: tenant={}, customer={}, doc={}, path={}",
                tenantId, customerId, docId, relativePath);

        return relativePath;
    }

    @Override
    public byte[] retrieve(String storagePath, byte[] blobData) {
        // CBS: If storagePath is "BLOB" or null, fall back to entity BLOB data.
        // This supports hybrid mode during migration from DATABASE to FILESYSTEM —
        // old documents have BLOB data, new documents have filesystem paths.
        if (storagePath == null || "BLOB".equals(storagePath)) {
            if (blobData != null && blobData.length > 0) {
                log.debug("Document retrieved from DB BLOB (legacy/migration): path={}", storagePath);
                return blobData;
            }
            throw new BusinessException("STORAGE_NOT_FOUND",
                    "Document has no storage path and no BLOB data. Data may be corrupted.");
        }

        Path filePath = basePath.resolve(storagePath).normalize();

        // CBS Security: Validate resolved path is within basePath.
        if (!filePath.startsWith(basePath)) {
            throw new BusinessException("STORAGE_PATH_VIOLATION",
                    "Storage path escapes base directory. Possible tampering detected.");
        }

        if (!Files.exists(filePath)) {
            throw new BusinessException("STORAGE_NOT_FOUND",
                    "Document file not found at: " + storagePath
                            + ". File may have been deleted or storage path corrupted.");
        }

        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_READ_ERROR",
                    "Failed to read document from filesystem: " + e.getMessage());
        }
    }

    @Override
    public void delete(String storagePath) {
        if (storagePath == null || "BLOB".equals(storagePath)) {
            return; // No external file to delete
        }

        Path filePath = basePath.resolve(storagePath).normalize();
        if (!filePath.startsWith(basePath)) {
            log.warn("Refusing to delete file outside base path: {}", storagePath);
            return;
        }

        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.debug("Document file deleted from filesystem: {}", storagePath);
            }
        } catch (IOException e) {
            // Per CBS: deletion failure is logged but not fatal.
            // Orphaned files are cleaned up by periodic maintenance batch.
            log.warn("Failed to delete document file: {} — {}", storagePath, e.getMessage());
        }
    }

    @Override
    public String getStorageType() {
        return "FILESYSTEM";
    }

    /**
     * Sanitizes a path component to prevent directory traversal.
     * Removes path separators and special characters.
     */
    private static String sanitizePathComponent(String component) {
        if (component == null || component.isBlank()) return "unknown";
        return component.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
