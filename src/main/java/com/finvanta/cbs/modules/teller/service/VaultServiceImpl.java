package com.finvanta.cbs.modules.teller.service;

import com.finvanta.audit.AuditService;
import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.cbs.modules.teller.domain.TellerCashMovement;
import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.domain.VaultPosition;
import com.finvanta.cbs.modules.teller.repository.TellerCashMovementRepository;
import com.finvanta.cbs.modules.teller.repository.TellerTillRepository;
import com.finvanta.cbs.modules.teller.repository.VaultPositionRepository;
import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CbsReferenceService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Vault Service Implementation per CBS VAULT standard.
 *
 * <p>Manages the branch-level cash safe and till↔vault cash movements.
 * Key invariants:
 * <ul>
 *   <li><b>No GL impact.</b> Vault↔till movements redistribute cash within
 *       the branch's GL BANK_OPERATIONS balance; the GL total is unchanged.</li>
 *   <li><b>Dual control.</b> Every movement has a maker (teller) and a checker
 *       (vault custodian). Balances only move on approval.</li>
 *   <li><b>Lock order: vault first, then till.</b> Prevents deadlocks when
 *       concurrent BUY and SELL movements target the same vault.</li>
 *   <li><b>Vault cannot go negative.</b> A BUY that would drive the vault
 *       below zero is rejected at approval time.</li>
 *   <li><b>Till cannot go negative on SELL.</b> A SELL that would drive the
 *       till below zero is rejected at approval time.</li>
 * </ul>
 */
@Service
public class VaultServiceImpl implements VaultService {

    private static final Logger log = LoggerFactory.getLogger(VaultServiceImpl.class);

    private final VaultPositionRepository vaultRepository;
    private final TellerTillRepository tillRepository;
    private final TellerCashMovementRepository movementRepository;
    private final BranchRepository branchRepository;
    private final BusinessDateService businessDateService;
    private final CbsReferenceService cbsReferenceService;
    private final AuditService auditService;

    public VaultServiceImpl(
            VaultPositionRepository vaultRepository,
            TellerTillRepository tillRepository,
            TellerCashMovementRepository movementRepository,
            BranchRepository branchRepository,
            BusinessDateService businessDateService,
            CbsReferenceService cbsReferenceService,
            AuditService auditService) {
        this.vaultRepository = vaultRepository;
        this.tillRepository = tillRepository;
        this.movementRepository = movementRepository;
        this.branchRepository = branchRepository;
        this.businessDateService = businessDateService;
        this.cbsReferenceService = cbsReferenceService;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public VaultPosition openVault(BigDecimal openingBalance) {
        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId == null) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "User has no branch assignment; cannot open vault");
        }
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Branch not found: " + branchId));
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // Idempotent: if vault already exists for today, return it.
        var existing = vaultRepository.findByBranchAndDate(tenantId, branchId, businessDate);
        if (existing.isPresent()) {
            return existing.get();
        }

        VaultPosition vault = new VaultPosition();
        vault.setTenantId(tenantId);
        vault.setBranch(branch);
        vault.setBranchCode(branch.getBranchCode());
        vault.setBusinessDate(businessDate);
        vault.setOpeningBalance(openingBalance);
        vault.setCurrentBalance(openingBalance);
        vault.setStatus("OPEN");
        vault.setOpenedBy(user);
        vault.setCreatedBy(user);
        vault.setUpdatedBy(user);
        VaultPosition saved = vaultRepository.save(vault);

        auditService.logEvent("VaultPosition", saved.getId(), "VAULT_OPENED",
                null, saved, "TELLER",
                "Vault opened at " + branch.getBranchCode() + " on " + businessDate
                        + " opening balance: INR " + openingBalance);
        log.info("CBS Vault opened: branch={} date={} balance={}",
                branch.getBranchCode(), businessDate, openingBalance);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public VaultPosition getMyBranchVault() {
        String tenantId = TenantContext.getCurrentTenant();
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();
        return vaultRepository.findByBranchAndDate(tenantId, branchId, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "No vault open for branch on " + businessDate));
    }

    @Override
    @Transactional
    public TellerCashMovement requestBuyCash(BigDecimal amount, String remarks) {
        return createMovementRequest("BUY", amount, remarks);
    }

    @Override
    @Transactional
    public TellerCashMovement requestSellCash(BigDecimal amount, String remarks) {
        return createMovementRequest("SELL", amount, remarks);
    }

    @Override
    @Transactional
    public TellerCashMovement approveMovement(Long movementId) {
        String tenantId = TenantContext.getCurrentTenant();
        String custodian = SecurityUtil.getCurrentUsername();

        TellerCashMovement mov = movementRepository.findById(movementId)
                .filter(m -> tenantId.equals(m.getTenantId()))
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Movement not found: " + movementId));

        if (!mov.isPending()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Movement is not PENDING (current: " + mov.getStatus() + ")");
        }
        // Maker ≠ checker
        if (custodian.equals(mov.getRequestedBy())) {
            throw new BusinessException(CbsErrorCodes.WF_SELF_APPROVAL,
                    "Vault custodian cannot approve their own movement request. "
                            + "Requester: " + mov.getRequestedBy());
        }

        LocalDate businessDate = businessDateService.getCurrentBusinessDate();
        Long branchId = SecurityUtil.getCurrentUserBranchId();

        // Lock order: vault first, then till — prevents deadlocks.
        VaultPosition vault = vaultRepository
                .findAndLockByBranchAndDate(tenantId, branchId, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Vault not found for approval"));
        TellerTill till = tillRepository
                .findAndLockByTellerAndDate(tenantId, mov.getRequestedBy(), businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                        "Till not found for teller " + mov.getRequestedBy()));

        if (mov.isBuy()) {
            // Vault → till: vault decrements, till increments.
            if (vault.getCurrentBalance().compareTo(mov.getAmount()) < 0) {
                throw new BusinessException(CbsErrorCodes.TELLER_TILL_INSUFFICIENT_CASH,
                        "Vault has INR " + vault.getCurrentBalance()
                                + "; cannot dispense INR " + mov.getAmount());
            }
            vault.setCurrentBalance(vault.getCurrentBalance().subtract(mov.getAmount()));
            till.setCurrentBalance(till.getCurrentBalance().add(mov.getAmount()));
        } else {
            // Till → vault: till decrements, vault increments.
            if (till.getCurrentBalance().compareTo(mov.getAmount()) < 0) {
                throw new BusinessException(CbsErrorCodes.TELLER_TILL_INSUFFICIENT_CASH,
                        "Till has INR " + till.getCurrentBalance()
                                + "; cannot return INR " + mov.getAmount());
            }
            till.setCurrentBalance(till.getCurrentBalance().subtract(mov.getAmount()));
            vault.setCurrentBalance(vault.getCurrentBalance().add(mov.getAmount()));
        }

        vault.setUpdatedBy(custodian);
        till.setUpdatedBy(custodian);
        vaultRepository.save(vault);
        tillRepository.save(till);

        mov.setStatus("APPROVED");
        mov.setApprovedBy(custodian);
        mov.setApprovedAt(LocalDateTime.now());
        mov.setUpdatedBy(custodian);
        TellerCashMovement saved = movementRepository.save(mov);

        auditService.logEventInline("TellerCashMovement", saved.getId(), "APPROVED",
                "PENDING", "APPROVED", "TELLER",
                mov.getMovementType() + " INR " + mov.getAmount()
                        + " approved by " + custodian
                        + " | till=" + till.getId()
                        + " | vault=" + vault.getId());
        log.info("CBS Vault movement {} approved: type={} amount={} custodian={}",
                saved.getMovementRef(), mov.getMovementType(), mov.getAmount(), custodian);
        return saved;
    }

    @Override
    @Transactional
    public TellerCashMovement rejectMovement(Long movementId, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        String custodian = SecurityUtil.getCurrentUsername();

        TellerCashMovement mov = movementRepository.findById(movementId)
                .filter(m -> tenantId.equals(m.getTenantId()))
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Movement not found: " + movementId));
        if (!mov.isPending()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Movement is not PENDING (current: " + mov.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Rejection reason is mandatory per RBI audit norms");
        }

        mov.setStatus("REJECTED");
        mov.setApprovedBy(custodian);
        mov.setApprovedAt(LocalDateTime.now());
        mov.setRejectionReason(reason);
        mov.setUpdatedBy(custodian);
        TellerCashMovement saved = movementRepository.save(mov);

        auditService.logEvent("TellerCashMovement", saved.getId(), "REJECTED",
                "PENDING", "REJECTED", "TELLER",
                mov.getMovementType() + " INR " + mov.getAmount()
                        + " rejected by " + custodian + ": " + reason);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TellerCashMovement> getPendingMovements() {
        String tenantId = TenantContext.getCurrentTenant();
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();
        return movementRepository.findPendingByBranchAndDate(tenantId, branchId, businessDate);
    }

    @Override
    @Transactional
    public VaultPosition closeVault(BigDecimal countedBalance, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String custodian = SecurityUtil.getCurrentUsername();
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        VaultPosition vault = vaultRepository
                .findAndLockByBranchAndDate(tenantId, branchId, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "No vault open for branch on " + businessDate));

        if (vault.isClosed()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Vault is already CLOSED");
        }

        // Per RBI Internal Controls: all tills must be CLOSED before the
        // vault can close. This enforces the ordering: tellers close first,
        // then the vault custodian reconciles.
        long activeTills = tillRepository.countActiveAtBranch(tenantId, branchId, businessDate);
        if (activeTills > 0) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    activeTills + " till(s) at this branch are still OPEN/PENDING. "
                            + "All tellers must close their tills before the vault can close.");
        }

        BigDecimal variance = countedBalance.subtract(vault.getCurrentBalance());
        vault.setCountedBalance(countedBalance);
        vault.setVarianceAmount(variance);
        vault.setStatus("CLOSED");
        vault.setClosedBy(custodian);
        vault.setRemarks(remarks);
        vault.setUpdatedBy(custodian);
        VaultPosition saved = vaultRepository.save(vault);

        String varianceNote = variance.signum() == 0
                ? "zero variance"
                : (variance.signum() > 0 ? "OVERAGE INR " + variance : "SHORTAGE INR " + variance.abs());

        auditService.logEventInline("VaultPosition", saved.getId(), "VAULT_CLOSED",
                "OPEN", "CLOSED", "TELLER",
                "Vault closed by " + custodian + " at " + vault.getBranchCode()
                        + " | system=" + vault.getCurrentBalance()
                        + " | counted=" + countedBalance
                        + " | " + varianceNote);

        log.info("CBS Vault CLOSED: branch={} date={} custodian={} {}",
                vault.getBranchCode(), businessDate, custodian, varianceNote);
        return saved;
    }

    // =====================================================================
    // Private helpers
    // =====================================================================

    /**
     * Creates a PENDING cash movement request. The teller's till and the
     * branch vault are resolved from the authenticated principal. No
     * balance mutation occurs at this stage — balances only move when the
     * vault custodian approves via {@link #approveMovement}.
     */
    private TellerCashMovement createMovementRequest(String movementType, BigDecimal amount, String remarks) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Movement amount must be positive");
        }
        String tenantId = TenantContext.getCurrentTenant();
        String tellerUser = SecurityUtil.getCurrentUsername();
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // Resolve the teller's OPEN till.
        TellerTill till = tillRepository
                .findByTellerAndDate(tenantId, tellerUser, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                        "No till open for teller " + tellerUser));
        if (!till.isOpen()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Till is not OPEN (current: " + till.getStatus() + ")");
        }

        // Resolve the branch vault.
        VaultPosition vault = vaultRepository
                .findByBranchAndDate(tenantId, branchId, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "No vault open for branch on " + businessDate));
        if (!vault.isOpen()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Vault is not OPEN (current: " + vault.getStatus() + ")");
        }

        String movementRef = cbsReferenceService.generateVaultMovementRef(
                till.getBranchCode(), businessDate);

        TellerCashMovement mov = new TellerCashMovement();
        mov.setTenantId(tenantId);
        mov.setMovementRef(movementRef);
        mov.setMovementType(movementType);
        mov.setBranch(till.getBranch());
        mov.setBranchCode(till.getBranchCode());
        mov.setTillId(till.getId());
        mov.setVaultId(vault.getId());
        mov.setBusinessDate(businessDate);
        mov.setAmount(amount);
        mov.setStatus("PENDING");
        mov.setRequestedBy(tellerUser);
        mov.setRequestedAt(LocalDateTime.now());
        mov.setRemarks(remarks);
        mov.setCreatedBy(tellerUser);
        mov.setUpdatedBy(tellerUser);
        TellerCashMovement saved = movementRepository.save(mov);

        auditService.logEvent("TellerCashMovement", saved.getId(), "REQUESTED",
                null, saved, "TELLER",
                movementType + " INR " + amount + " requested by " + tellerUser
                        + " | till=" + till.getId() + " | vault=" + vault.getId());
        log.info("CBS Vault movement requested: ref={} type={} amount={} teller={}",
                movementRef, movementType, amount, tellerUser);
        return saved;
    }
}
