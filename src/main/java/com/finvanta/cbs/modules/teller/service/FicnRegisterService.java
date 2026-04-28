package com.finvanta.cbs.modules.teller.service;

import com.finvanta.audit.AuditService;
import com.finvanta.cbs.modules.teller.domain.CounterfeitNoteRegister;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.DenominationEntry;
import com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse;
import com.finvanta.cbs.modules.teller.repository.CounterfeitNoteRegisterRepository;
import com.finvanta.domain.entity.Branch;
import com.finvanta.service.CbsReferenceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS FICN Register Service per RBI Master Direction on Counterfeit Notes.
 *
 * <p>Single-purpose service: writes immutable {@code CounterfeitNoteRegister}
 * rows when counterfeit notes are detected at the teller counter, and emits
 * the printable {@link FicnAcknowledgementResponse} for the customer slip.
 *
 * <p><b>Why a separate service?</b> {@code recordDetection} MUST commit the
 * register row in a NEW transaction so the row survives the
 * {@code FicnDetectedException} rollback the caller throws. Spring's
 * {@link Propagation#REQUIRES_NEW} only takes effect when invoked through
 * the bean proxy -- self-invocation inside {@code TellerServiceImpl} would
 * silently bypass the proxy and the row would roll back with the parent.
 *
 * <p>Same pattern as {@code AuditService.logEvent} and
 * {@code SequenceGeneratorService.nextValue}: any helper that must commit
 * independently of its caller's transaction lives in its own bean.
 *
 * <p>Per RBI: the register reference is permanent. Once minted (by
 * {@link CbsReferenceService#generateFicnRegisterRef}), it is printed on the
 * customer receipt and the FIR copy. The DB triggers
 * ({@code trg_ficn_no_update}, {@code trg_ficn_no_delete}) enforce
 * INSERT-ONLY at the storage layer; this service never loads-then-mutates
 * an existing row.
 */
@Service
public class FicnRegisterService {

    private static final Logger log = LoggerFactory.getLogger(FicnRegisterService.class);

    private final CounterfeitNoteRegisterRepository registerRepository;
    private final CbsReferenceService cbsReferenceService;
    private final AuditService auditService;

    public FicnRegisterService(
            CounterfeitNoteRegisterRepository registerRepository,
            CbsReferenceService cbsReferenceService,
            AuditService auditService) {
        this.registerRepository = registerRepository;
        this.cbsReferenceService = cbsReferenceService;
        this.auditService = auditService;
    }

    /**
     * Writes one {@link CounterfeitNoteRegister} row per counterfeit-flagged
     * denomination entry under a {@link Propagation#REQUIRES_NEW} boundary so
     * the rows commit independently of the caller's parent transaction.
     * Returns the printable acknowledgement slip for the customer.
     *
     * <p>The caller (TellerServiceImpl.cashDeposit) is expected to throw
     * {@link com.finvanta.cbs.modules.teller.exception.FicnDetectedException}
     * AFTER this method returns. That exception triggers a rollback of the
     * parent transaction -- but the register rows persist because they were
     * committed in this sub-transaction.
     *
     * <p>Audit uses {@code logEventInline} (REQUIRED) so the audit row joins
     * THIS sub-transaction and commits alongside the register row. No
     * PESSIMISTIC_WRITE locks are held during this method (we're writing to
     * a fresh table), so the inline audit is deadlock-safe.
     *
     * <p>Body intentionally placed in a follow-up edit to keep this file
     * within the tooling's per-edit JSON-payload limit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FicnAcknowledgementResponse recordDetection(
            CashDepositRequest request,
            Branch branch,
            String branchCode,
            Long tillId,
            String tellerUser,
            LocalDate businessDate,
            String tenantId) {
        // Pick the originating-txn reference: prefer the request's idempotency
        // key (always present per CashDepositRequest validation) so the
        // register entry can be cross-referenced to the rejected deposit
        // attempt without needing a real transactionRef -- which doesn't
        // exist for a rejected deposit (no engine call was made).
        String originatingTxnRef = request.idempotencyKey();

        LocalDateTime detectionTimestamp = LocalDateTime.now();
        BigDecimal totalImpoundedValue = BigDecimal.ZERO;
        long totalImpoundedCount = 0L;
        List<FicnAcknowledgementResponse.FicnDenominationLine> ackLines = new ArrayList<>();
        List<CounterfeitNoteRegister> persisted = new ArrayList<>();

        // One register row per counterfeit-flagged denomination batch. Per
        // RBI: each denomination is a separate line on the quarterly FICN
        // return, so separate rows keep reporting trivial.
        for (DenominationEntry entry : request.denominations()) {
            if (entry == null || entry.counterfeitCount() <= 0) {
                continue;
            }

            String registerRef = cbsReferenceService.generateFicnRegisterRef(
                    branchCode, businessDate);
            BigDecimal totalFaceValue = entry.denomination()
                    .totalFor(entry.counterfeitCount());

            CounterfeitNoteRegister r = new CounterfeitNoteRegister();
            r.setTenantId(tenantId);
            r.setRegisterRef(registerRef);
            r.setOriginatingTxnRef(originatingTxnRef);
            r.setBranch(branch);
            r.setBranchCode(branchCode);
            r.setTillId(tillId);
            r.setDetectedByTeller(tellerUser);
            r.setDetectionDate(businessDate);
            r.setDetectionTimestamp(detectionTimestamp);
            r.setDenomination(entry.denomination());
            r.setCounterfeitCount(entry.counterfeitCount());
            r.setTotalFaceValue(totalFaceValue);
            r.setDepositorName(request.depositorName());
            r.setDepositorMobile(request.depositorMobile());
            // PMLA Rule 9 ID capture: prefer PAN, fall back to Form 60 ref.
            // Both fields are optional below the CTR threshold but RBI
            // mandates capture for FICN regardless.
            if (request.panNumber() != null && !request.panNumber().isBlank()) {
                r.setDepositorIdType("PAN");
                r.setDepositorIdNumber(request.panNumber());
            } else if (request.form60Reference() != null
                    && !request.form60Reference().isBlank()) {
                r.setDepositorIdType("FORM_60");
                r.setDepositorIdNumber(request.form60Reference());
            }
            // chestDispatchStatus defaults to PENDING via the entity's field
            // initializer; no explicit set needed.
            r.setCreatedBy(tellerUser);
            r.setUpdatedBy(tellerUser);

            CounterfeitNoteRegister saved = registerRepository.save(r);
            persisted.add(saved);

            ackLines.add(new FicnAcknowledgementResponse.FicnDenominationLine(
                    entry.denomination(),
                    entry.counterfeitCount(),
                    totalFaceValue));
            totalImpoundedValue = totalImpoundedValue.add(totalFaceValue);
            totalImpoundedCount += entry.counterfeitCount();

            // Inline audit per row -- joins THIS sub-transaction so the audit
            // log commits with the register row.
            auditService.logEventInline(
                    "CounterfeitNoteRegister", saved.getId(), "DETECTED",
                    null, saved, "TELLER",
                    "FICN detected: " + entry.counterfeitCount() + " x "
                            + entry.denomination() + " (INR " + totalFaceValue + ") "
                            + "ref=" + registerRef + " teller=" + tellerUser
                            + " depositor=" + request.depositorName()
                            + (saved.requiresFir() ? " | FIR mandatory (>=5 notes)" : ""));

            log.warn("CBS FICN detected: ref={} branch={} denom={} count={} value={} fir-required={}",
                    registerRef, branchCode, entry.denomination(),
                    entry.counterfeitCount(), totalFaceValue, saved.requiresFir());
        }

        // Build the customer slip from the FIRST register row's metadata.
        // When a single deposit triggers multiple register rows (rare), the
        // slip uses the first row's ref as the primary reference. All
        // batches are listed in `ackLines` so the customer has a single
        // slip covering every impounded denomination.
        CounterfeitNoteRegister primary = persisted.get(0);
        boolean firRequired = totalImpoundedCount >= CounterfeitNoteRegister.FIR_MANDATORY_THRESHOLD;

        return new FicnAcknowledgementResponse(
                primary.getRegisterRef(),
                originatingTxnRef,
                branchCode,
                branch.getBranchName(),
                businessDate,
                detectionTimestamp,
                tellerUser,
                request.depositorName(),
                primary.getDepositorIdType(),
                primary.getDepositorIdNumber(),
                request.depositorMobile(),
                ackLines,
                totalImpoundedValue,
                firRequired,
                primary.getChestDispatchStatus(),
                /* remarks */ null);
    }
}
