package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.CustomerDocument;
import com.finvanta.domain.enums.DocumentType;
import com.finvanta.domain.enums.DocumentVerificationStatus;
import com.finvanta.repository.CustomerDocumentRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CustomerCifService;
import com.finvanta.service.CustomerDocumentService;
import com.finvanta.service.DocumentStorageService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Customer Document Service Implementation per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE.
 *
 * All document business logic resides here — NOT in the controller.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller → Service → Repository
 *   Controller has NO @Transactional, NO direct repository calls, NO business logic.
 *
 * Per RBI KYC Master Direction 2016:
 * - Document upload, verification, and download are transactional
 * - Maker-checker enforcement on document verification
 * - Branch access enforced on all operations via CustomerCifService.getCustomer()
 * - Every state change audited via AuditService (REQUIRES_NEW propagation)
 *
 * Per RBI IT Governance Direction 2023:
 * - File content validated by magic bytes (not just Content-Type header)
 * - Filenames sanitized to prevent path traversal / header injection
 * - Storage backend configurable via DocumentStorageService
 */
@Service
public class CustomerDocumentServiceImpl implements CustomerDocumentService {

    private static final Logger log = LoggerFactory.getLogger(CustomerDocumentServiceImpl.class);

    /** Max file size: 5MB per RBI IT Governance Direction 2023 */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final CustomerDocumentRepository documentRepo;
    private final CustomerCifService customerService;
    private final DocumentStorageService storageService;
    private final BusinessDateService businessDateService;
    private final AuditService auditSvc;

    public CustomerDocumentServiceImpl(
            CustomerDocumentRepository documentRepo,
            CustomerCifService customerService,
            DocumentStorageService storageService,
            BusinessDateService businessDateService,
            AuditService auditSvc) {
        this.documentRepo = documentRepo;
        this.customerService = customerService;
        this.storageService = storageService;
        this.businessDateService = businessDateService;
        this.auditSvc = auditSvc;
    }

    @Override
    @Transactional
    public CustomerDocument uploadDocument(Long customerId, String documentType,
            String fileName, String contentType, long fileSize, byte[] fileData,
            String documentNumber, String remarks) {

        // CBS Validation: document type against OVD enum
        DocumentType docType = DocumentType.fromString(documentType);
        if (docType == null) {
            throw new BusinessException("INVALID_DOC_TYPE",
                    "Invalid document type: " + documentType + ". Must be a valid OVD type.");
        }

        // CBS Validation: file size per RBI IT Governance
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE",
                    "File size exceeds 5MB limit. Please compress or resize the document.");
        }

        // CBS Validation: allowed MIME types (PDF, JPG, PNG)
        if (contentType == null || (!contentType.equals("application/pdf")
                && !contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
            throw new BusinessException("INVALID_FORMAT", "Only PDF, JPG, and PNG files are allowed.");
        }

        // CBS: Validate file content by magic bytes — Content-Type is client-controlled
        if (!isValidFileContent(fileData, contentType)) {
            throw new BusinessException("CONTENT_MISMATCH",
                    "File content does not match the declared type. Possible file spoofing detected.");
        }

        // CBS: Branch access enforcement via getCustomer()
        Customer customer = customerService.getCustomer(customerId);
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        String safeFileName = sanitizeFileName(fileName);

        CustomerDocument doc = new CustomerDocument();
        doc.setTenantId(tenantId);
        doc.setCustomer(customer);
        doc.setDocumentType(docType);
        doc.setFileName(safeFileName);
        doc.setContentType(contentType);
        doc.setFileSize(fileSize);
        doc.setDocumentNumber(documentNumber);
        doc.setRemarks(remarks);
        doc.setVerificationStatus(DocumentVerificationStatus.UPLOADED);
        doc.setCreatedBy(user);

        // CBS: Store file content via configurable backend.
        // For DATABASE mode: set BLOB data and storagePath="BLOB" before first save
        // to avoid a redundant UPDATE. For FILESYSTEM mode: first save to get the
        // entity ID (needed for the filesystem path), then store and update path.
        if ("DATABASE".equals(storageService.getStorageType())) {
            doc.setFileData(fileData);
            doc.setStoragePath("BLOB");
            documentRepo.save(doc);
        } else {
            // FILESYSTEM: need entity ID for path generation → save first, then store
            documentRepo.save(doc);
            String storagePath = storageService.store(
                    tenantId, customer.getId(), doc.getId(), safeFileName, fileData);
            doc.setStoragePath(storagePath);
            documentRepo.save(doc);
        }

        auditSvc.logEvent("CustomerDocument", doc.getId(), "UPLOAD", null,
                documentType, "CIF",
                "Document uploaded: " + documentType + " for customer " + customer.getCustomerNumber()
                        + " | File: " + safeFileName + " | Size: " + fileSize + " bytes"
                        + " | Storage: " + storageService.getStorageType());

        log.info("Document uploaded: type={}, customer={}, file={}, storage={}",
                documentType, customer.getCustomerNumber(), safeFileName, storageService.getStorageType());
        return doc;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDocument getDocument(Long docId) {
        String tenantId = TenantContext.getCurrentTenant();
        CustomerDocument doc = documentRepo.findById(docId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "Document not found"));
        // CBS: Branch access enforcement
        customerService.getCustomer(doc.getCustomer().getId());
        return doc;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] retrieveFileContent(CustomerDocument doc) {
        // CBS: @Transactional ensures Hibernate session is open when accessing doc.getFileData().
        // With spring.jpa.open-in-view=false (set globally), the session from getDocument()
        // is already closed when the controller calls this method. Currently fileData is a
        // byte[] (eagerly loaded), but if @Basic(fetch=LAZY) is ever added for performance,
        // accessing it on a detached entity would throw LazyInitializationException.
        // Per Finacle/Temenos Tier-1: all data access must be within a transaction boundary.
        return storageService.retrieve(doc.getStoragePath(), doc.getFileData());
    }

    @Override
    @Transactional
    public CustomerDocument verifyDocument(Long docId, String action, String rejectionReason) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        CustomerDocument doc = documentRepo.findById(docId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "Document not found"));

        // CBS: Branch access enforcement
        customerService.getCustomer(doc.getCustomer().getId());

        // CBS Tier-1 Maker-Checker: verifier must NOT be the same user who uploaded
        if (user.equals(doc.getCreatedBy())) {
            throw new BusinessException("SELF_VERIFY_PROHIBITED",
                    "Document verification cannot be performed by the same user who uploaded it ("
                            + user + "). Per RBI internal controls, maker and checker must be different.");
        }

        // CBS: Immutability — only UPLOADED documents can be processed
        if (!doc.getVerificationStatus().canProcess()) {
            throw new BusinessException("DOC_ALREADY_PROCESSED",
                    "Document already " + doc.getVerificationStatus().name().toLowerCase()
                            + ". Upload a new version if correction is needed.");
        }

        if ("VERIFY".equals(action)) {
            doc.setVerificationStatus(DocumentVerificationStatus.VERIFIED);
        } else if ("REJECT".equals(action)) {
            if (rejectionReason == null || rejectionReason.isBlank()) {
                throw new BusinessException("REASON_REQUIRED",
                        "Rejection reason is mandatory per RBI audit norms.");
            }
            doc.setVerificationStatus(DocumentVerificationStatus.REJECTED);
            doc.setRejectionReason(rejectionReason);
        } else {
            throw new BusinessException("INVALID_ACTION", "Action must be VERIFY or REJECT.");
        }

        doc.setVerifiedBy(user);
        doc.setVerifiedDate(businessDateService.getCurrentBusinessDate());
        doc.setUpdatedBy(user);
        documentRepo.save(doc);

        auditSvc.logEvent("CustomerDocument", doc.getId(),
                "VERIFY".equals(action) ? "DOC_VERIFIED" : "DOC_REJECTED",
                "UPLOADED", doc.getVerificationStatus().name(), "CIF",
                "Document " + doc.getVerificationStatus().name().toLowerCase() + ": "
                        + doc.getDocumentType().name() + " | By: " + user);

        log.info("Document {}: type={}, docId={}, user={}",
                doc.getVerificationStatus().name().toLowerCase(),
                doc.getDocumentType().name(), docId, user);
        return doc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerDocument> getDocumentsForCustomer(Long customerId) {
        String tenantId = TenantContext.getCurrentTenant();
        // Branch access enforced via getCustomer()
        customerService.getCustomer(customerId);
        return documentRepo.findByCustomer(tenantId, customerId);
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "document";
        String name = fileName;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        name = name.replaceAll("[^a-zA-Z0-9._\\- ]", "_");
        name = name.replaceAll("_+", "_").trim();
        if (name.isBlank() || name.equals("_")) return "document";
        if (name.length() > 200) name = name.substring(0, 200);
        return name;
    }

    private static boolean isValidFileContent(byte[] data, String declaredContentType) {
        if (data == null || data.length < 8) return false;
        if ("application/pdf".equals(declaredContentType)) {
            return data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46;
        } else if ("image/jpeg".equals(declaredContentType)) {
            return (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
        } else if ("image/png".equals(declaredContentType)) {
            return (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47
                    && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A;
        }
        return false;
    }
}
