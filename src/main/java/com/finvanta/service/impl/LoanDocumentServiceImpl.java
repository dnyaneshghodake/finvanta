package com.finvanta.service.impl;

import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.entity.LoanDocument;
import com.finvanta.domain.enums.DocumentType;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.repository.LoanDocumentRepository;
import com.finvanta.service.DocumentStorageService;
import com.finvanta.service.LoanDocumentService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Loan Document Service Implementation.
 *
 * Manages loan application documents per Finacle DOCUPLOAD / Temenos AA.DOCUMENT.
 * All operations enforce tenant isolation and transactional atomicity.
 */
@Service
public class LoanDocumentServiceImpl implements LoanDocumentService {

    private static final Logger log = LoggerFactory.getLogger(LoanDocumentServiceImpl.class);

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final LoanDocumentRepository documentRepository;
    private final LoanApplicationRepository applicationRepository;
    private final DocumentStorageService documentStorageService;

    public LoanDocumentServiceImpl(
            LoanDocumentRepository documentRepository,
            LoanApplicationRepository applicationRepository,
            DocumentStorageService documentStorageService) {
        this.documentRepository = documentRepository;
        this.applicationRepository = applicationRepository;
        this.documentStorageService = documentStorageService;
    }

    @Override
    @Transactional
    public LoanDocument uploadDocument(
            Long loanApplicationId,
            DocumentType documentType,
            String fileName,
            byte[] fileContent,
            String contentType,
            String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        // Validate loan application exists and belongs to tenant
        LoanApplication application = applicationRepository.findById(loanApplicationId)
                .filter(app -> app.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "APPLICATION_NOT_FOUND",
                        "Loan application not found: " + loanApplicationId));

        // Validate file size
        if (fileContent == null || fileContent.length == 0) {
            throw new BusinessException("EMPTY_FILE", "File content is empty");
        }
        if (fileContent.length > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE",
                    "File exceeds maximum size of " + (MAX_FILE_SIZE_BYTES / 1024 / 1024) + " MB");
        }

        // Store document content - use the correct interface signature
        // store(String tenantId, Long customerId, Long docId, String fileName, byte[] fileData)
        String storageKey = documentStorageService.store(
                tenantId,
                application.getId(),
                null, // docId not available yet
                fileName,
                fileContent);

        // Create document record
        LoanDocument doc = new LoanDocument();
        doc.setLoanApplication(application);
        doc.setDocumentType(documentType != null ? documentType.name() : null);
        doc.setDocumentName(fileName);
        doc.setFileName(fileName);
        doc.setFilePath(storageKey);
        doc.setContentType(contentType);
        doc.setFileSize((long) fileContent.length);
        doc.setRemarks(remarks);
        doc.setVerificationStatus(com.finvanta.domain.enums.DocumentVerificationStatus.PENDING);

        LoanDocument saved = documentRepository.save(doc);

        log.info("LoanDocument uploaded: id={}, appId={}, type={}, size={}, by={}",
                saved.getId(), loanApplicationId, documentType, fileContent.length, user);

        return saved;
    }

    @Override
    @Transactional
    public LoanDocument updateDocumentRemarks(Long documentId, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        LoanDocument doc = documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId));

        doc.setRemarks(remarks);
        doc.setUpdatedBy(user);

        LoanDocument saved = documentRepository.save(doc);

        log.info("LoanDocument remarks updated: id={}, by={}", documentId, user);

        return saved;
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        LoanDocument doc = documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId));

        // Delete from storage
        if (doc.getFilePath() != null) {
            documentStorageService.delete(doc.getFilePath());
        }

        // Delete record
        documentRepository.delete(doc);

        log.info("LoanDocument deleted: id={}, by={}", documentId, user);
    }

    @Override
    @Transactional
    public void verifyDocument(Long documentId) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        LoanDocument doc = documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId));

        doc.setVerificationStatus(com.finvanta.domain.enums.DocumentVerificationStatus.VERIFIED);
        doc.setVerifiedBy(user);
        doc.setVerifiedDate(java.time.LocalDate.now());
        doc.setUpdatedBy(user);

        documentRepository.save(doc);

        log.info("LoanDocument verified: id={}, by={}", documentId, user);
    }

    @Override
    @Transactional
    public void rejectDocument(Long documentId, String rejectionReason) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();

        LoanDocument doc = documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId));

        doc.setVerificationStatus(com.finvanta.domain.enums.DocumentVerificationStatus.REJECTED);
        doc.setVerifiedBy(user);
        doc.setVerifiedDate(java.time.LocalDate.now());
        doc.setRejectionReason(rejectionReason);
        doc.setUpdatedBy(user);

        documentRepository.save(doc);

        log.info("LoanDocument rejected: id={}, reason={}, by={}", documentId, rejectionReason, user);
    }

    @Override
    public List<LoanDocument> listDocuments(Long loanApplicationId) {
        String tenantId = TenantContext.getCurrentTenant();
        return documentRepository.findByTenantIdAndLoanApplicationId(tenantId, loanApplicationId);
    }

    @Override
    public LoanDocument getDocument(Long documentId) {
        String tenantId = TenantContext.getCurrentTenant();
        return documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId));
    }
}