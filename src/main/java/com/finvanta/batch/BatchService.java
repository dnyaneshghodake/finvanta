package com.finvanta.batch;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BatchJob;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.BatchStatus;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.domain.rules.NpaClassificationRule;
import com.finvanta.repository.BatchJobRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.LoanAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final BatchJobRepository batchJobRepository;
    private final BusinessCalendarRepository calendarRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanAccountService loanAccountService;
    private final NpaClassificationRule npaRule;
    private final AuditService auditService;

    public BatchService(BatchJobRepository batchJobRepository,
                        BusinessCalendarRepository calendarRepository,
                        LoanAccountRepository loanAccountRepository,
                        LoanAccountService loanAccountService,
                        NpaClassificationRule npaRule,
                        AuditService auditService) {
        this.batchJobRepository = batchJobRepository;
        this.calendarRepository = calendarRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanAccountService = loanAccountService;
        this.npaRule = npaRule;
        this.auditService = auditService;
    }

    @Transactional
    public BatchJob runEodBatch(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String initiatedBy = SecurityUtil.getCurrentUsername();

        log.info("EOD batch started: tenant={}, date={}", tenantId, businessDate);

        // Step 1: Validate business date
        BusinessCalendar calendar = calendarRepository
            .findAndLockByTenantIdAndDate(tenantId, businessDate)
            .orElseThrow(() -> new BusinessException("BATCH_INVALID_DATE",
                "Business date not found in calendar: " + businessDate));

        if (calendar.isEodComplete()) {
            throw new BusinessException("BATCH_ALREADY_COMPLETE",
                "EOD already completed for date: " + businessDate);
        }

        if (calendar.isHoliday()) {
            throw new BusinessException("BATCH_HOLIDAY",
                "Cannot run EOD on a holiday: " + businessDate);
        }

        // Step 2: Lock business date
        calendar.setLocked(true);
        calendarRepository.save(calendar);

        BatchJob eodJob = new BatchJob();
        eodJob.setTenantId(tenantId);
        eodJob.setJobName("EOD_BATCH");
        eodJob.setBusinessDate(businessDate);
        eodJob.setStatus(BatchStatus.RUNNING);
        eodJob.setStartedAt(LocalDateTime.now());
        eodJob.setInitiatedBy(initiatedBy);
        eodJob.setCreatedBy(initiatedBy);
        eodJob = batchJobRepository.save(eodJob);

        int totalRecords = 0;
        int processedRecords = 0;
        int failedRecords = 0;
        StringBuilder errorLog = new StringBuilder();

        try {
            List<LoanAccount> activeAccounts = loanAccountRepository.findAllActiveAccounts(tenantId);
            totalRecords = activeAccounts.size();

            // Step 3: Run interest accrual
            eodJob.setStepName("INTEREST_ACCRUAL");
            batchJobRepository.save(eodJob);

            for (LoanAccount account : activeAccounts) {
                try {
                    loanAccountService.applyInterestAccrual(account.getAccountNumber(), businessDate);
                    processedRecords++;
                } catch (Exception e) {
                    failedRecords++;
                    errorLog.append("Interest accrual failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("Interest accrual failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 4: Run EMI due date update / DPD calculation
            eodJob.setStepName("DPD_CALCULATION");
            batchJobRepository.save(eodJob);

            for (LoanAccount account : activeAccounts) {
                try {
                    updateDaysPastDue(account, businessDate);
                } catch (Exception e) {
                    errorLog.append("DPD update failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("DPD update failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 5: Run NPA tagging
            eodJob.setStepName("NPA_CLASSIFICATION");
            batchJobRepository.save(eodJob);

            List<LoanAccount> npaCandidates = loanAccountRepository
                .findNpaCandidates(tenantId, npaRule.getNpaThresholdDays());

            for (LoanAccount account : npaCandidates) {
                try {
                    loanAccountService.classifyNPA(account.getAccountNumber());
                } catch (Exception e) {
                    errorLog.append("NPA classification failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("NPA classification failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 6: Mark EOD complete
            calendar.setEodComplete(true);
            calendarRepository.save(calendar);

            BatchStatus finalStatus = failedRecords > 0
                ? BatchStatus.PARTIALLY_COMPLETED : BatchStatus.COMPLETED;

            eodJob.setStatus(finalStatus);
            eodJob.setStepName("COMPLETED");
            eodJob.setCompletedAt(LocalDateTime.now());
            eodJob.setTotalRecords(totalRecords);
            eodJob.setProcessedRecords(processedRecords);
            eodJob.setFailedRecords(failedRecords);
            if (errorLog.length() > 0) {
                eodJob.setErrorMessage(errorLog.toString());
            }
            batchJobRepository.save(eodJob);

            auditService.logEvent("BatchJob", eodJob.getId(), "EOD_COMPLETE",
                null, eodJob, "BATCH",
                "EOD completed: processed=" + processedRecords + ", failed=" + failedRecords);

            log.info("EOD batch completed: date={}, processed={}, failed={}",
                businessDate, processedRecords, failedRecords);

        } catch (Exception e) {
            eodJob.setStatus(BatchStatus.FAILED);
            eodJob.setCompletedAt(LocalDateTime.now());
            eodJob.setTotalRecords(totalRecords);
            eodJob.setProcessedRecords(processedRecords);
            eodJob.setFailedRecords(failedRecords);
            eodJob.setErrorMessage(e.getMessage());
            batchJobRepository.save(eodJob);

            calendar.setLocked(false);
            calendarRepository.save(calendar);

            log.error("EOD batch failed: date={}", businessDate, e);

            if (eodJob.getRetryCount() < eodJob.getMaxRetries()) {
                eodJob.setRetryCount(eodJob.getRetryCount() + 1);
                batchJobRepository.save(eodJob);
                log.info("EOD batch will be retried: attempt {}/{}", eodJob.getRetryCount(), eodJob.getMaxRetries());
            }

            throw new BusinessException("BATCH_FAILED", "EOD batch failed: " + e.getMessage(), e);
        }

        return eodJob;
    }

    private void updateDaysPastDue(LoanAccount account, LocalDate businessDate) {
        if (account.getStatus() == LoanStatus.CLOSED || account.getStatus() == LoanStatus.WRITTEN_OFF) {
            return;
        }

        LocalDate lastPayment = account.getLastPaymentDate();
        LocalDate nextEmi = account.getNextEmiDate();

        if (nextEmi != null && businessDate.isAfter(nextEmi) && (lastPayment == null || lastPayment.isBefore(nextEmi))) {
            int dpd = (int) ChronoUnit.DAYS.between(nextEmi, businessDate);
            account.setDaysPastDue(dpd);
            account.setOverduePrincipal(account.getEmiAmount() != null ? account.getEmiAmount() : account.getOutstandingPrincipal());
            account.setUpdatedBy("SYSTEM");
            loanAccountRepository.save(account);
        }
    }

    public List<BatchJob> getBatchHistory() {
        return batchJobRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.getCurrentTenant());
    }

    public BatchJob getBatchJobByDate(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        return batchJobRepository.findByTenantIdAndJobNameAndBusinessDate(tenantId, "EOD_BATCH", businessDate)
            .orElse(null);
    }
}
