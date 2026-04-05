package com.finvanta.accounting;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.JournalEntryLine;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CBS General Ledger (GL) and Journal Entry Service.
 *
 * Per Finacle/Temenos accounting engine standards:
 * - All financial postings use double-entry bookkeeping (DR total == CR total)
 * - GL balances are updated atomically with pessimistic locking
 * - Journal entries are immutable once posted (reversal creates a new entry)
 * - Trial balance validation ensures GL integrity (Assets + Expenses = Liabilities + Income + Equity)
 *
 * Chart of Accounts follows Indian Banking Standard (see {@link GLConstants}):
 *   1xxx = Assets, 2xxx = Liabilities, 3xxx = Equity, 4xxx = Income, 5xxx = Expenses
 */
@Service
public class AccountingService {

    private static final Logger log = LoggerFactory.getLogger(AccountingService.class);

    private final JournalEntryRepository journalEntryRepository;
    private final GLMasterRepository glMasterRepository;
    private final AuditService auditService;
    private final LedgerService ledgerService;

    public AccountingService(JournalEntryRepository journalEntryRepository,
                             GLMasterRepository glMasterRepository,
                             AuditService auditService,
                             LedgerService ledgerService) {
        this.journalEntryRepository = journalEntryRepository;
        this.glMasterRepository = glMasterRepository;
        this.auditService = auditService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public JournalEntry postJournalEntry(LocalDate valueDate, String narration,
                                          String sourceModule, String sourceRef,
                                          List<JournalLineRequest> lines) {
        String tenantId = TenantContext.getCurrentTenant();

        if (lines == null || lines.size() < 2) {
            throw new BusinessException("ACCOUNTING_INVALID_ENTRY",
                "Journal entry must have at least 2 lines (double-entry)");
        }

        JournalEntry entry = new JournalEntry();
        entry.setTenantId(tenantId);
        entry.setJournalRef(ReferenceGenerator.generateJournalRef());
        entry.setValueDate(valueDate);
        entry.setPostingDate(LocalDateTime.now());
        entry.setNarration(narration);
        entry.setSourceModule(sourceModule);
        entry.setSourceRef(sourceRef);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        int lineNum = 1;

        for (JournalLineRequest lineReq : lines) {
            GLMaster gl = glMasterRepository.findByTenantIdAndGlCode(tenantId, lineReq.glCode())
                .orElseThrow(() -> new BusinessException("ACCOUNTING_GL_NOT_FOUND",
                    "GL account not found: " + lineReq.glCode()));

            if (!gl.isActive() || gl.isHeaderAccount()) {
                throw new BusinessException("ACCOUNTING_GL_NOT_POSTABLE",
                    "GL account " + lineReq.glCode() + " is not postable");
            }

            if (lineReq.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("ACCOUNTING_INVALID_AMOUNT",
                    "Journal line amount must be positive");
            }

            JournalEntryLine line = new JournalEntryLine();
            line.setTenantId(tenantId);
            line.setGlCode(lineReq.glCode());
            line.setGlName(gl.getGlName());
            line.setDebitCredit(lineReq.debitCredit());
            line.setAmount(lineReq.amount());
            line.setNarration(lineReq.narration());
            line.setLineNumber(lineNum++);

            entry.addLine(line);

            if (lineReq.debitCredit() == DebitCredit.DEBIT) {
                totalDebit = totalDebit.add(lineReq.amount());
            } else {
                totalCredit = totalCredit.add(lineReq.amount());
            }
        }

        validateDoubleEntry(totalDebit, totalCredit);

        entry.setTotalDebit(totalDebit);
        entry.setTotalCredit(totalCredit);
        entry.setPosted(true);

        JournalEntry savedEntry = journalEntryRepository.save(entry);

        updateGLBalances(tenantId, lines);

        // CBS: Post to immutable ledger (append-only with hash chain)
        ledgerService.postToLedger(savedEntry);

        auditService.logEvent("JournalEntry", savedEntry.getId(), "POST",
            null, savedEntry.getJournalRef(), "ACCOUNTING",
            "Journal entry posted: " + savedEntry.getJournalRef());

        log.info("Journal entry posted: ref={}, debit={}, credit={}",
            savedEntry.getJournalRef(), totalDebit, totalCredit);

        return savedEntry;
    }

    public void validateDoubleEntry(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException("ACCOUNTING_IMBALANCE",
                "Double-entry validation failed: Total Debit (" + totalDebit
                    + ") != Total Credit (" + totalCredit + ")");
        }
    }

    @Transactional
    public void updateGLBalances(String tenantId, List<JournalLineRequest> lines) {
        for (JournalLineRequest line : lines) {
            GLMaster gl = glMasterRepository.findAndLockByTenantIdAndGlCode(tenantId, line.glCode())
                .orElseThrow(() -> new BusinessException("ACCOUNTING_GL_NOT_FOUND",
                    "GL account not found: " + line.glCode()));

            if (line.debitCredit() == DebitCredit.DEBIT) {
                gl.setDebitBalance(gl.getDebitBalance().add(line.amount()));
            } else {
                gl.setCreditBalance(gl.getCreditBalance().add(line.amount()));
            }

            glMasterRepository.save(gl);
        }
    }

    public Map<String, Object> getTrialBalance() {
        String tenantId = TenantContext.getCurrentTenant();
        List<GLMaster> accounts = glMasterRepository.findAllPostableAccounts(tenantId);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        Map<String, Object> result = new HashMap<>();
        Map<String, Map<String, Object>> accountBalances = new HashMap<>();

        for (GLMaster gl : accounts) {
            Map<String, Object> balance = new HashMap<>();
            balance.put("glCode", gl.getGlCode());
            balance.put("glName", gl.getGlName());
            balance.put("accountType", gl.getAccountType().name());
            balance.put("debitBalance", gl.getDebitBalance());
            balance.put("creditBalance", gl.getCreditBalance());
            balance.put("netBalance", gl.getNetBalance());

            totalDebit = totalDebit.add(gl.getDebitBalance());
            totalCredit = totalCredit.add(gl.getCreditBalance());

            accountBalances.put(gl.getGlCode(), balance);
        }

        result.put("accounts", accountBalances);
        result.put("totalDebit", totalDebit);
        result.put("totalCredit", totalCredit);
        result.put("isBalanced", totalDebit.compareTo(totalCredit) == 0);

        return result;
    }

    public record JournalLineRequest(
        String glCode,
        DebitCredit debitCredit,
        BigDecimal amount,
        String narration
    ) {}
}
