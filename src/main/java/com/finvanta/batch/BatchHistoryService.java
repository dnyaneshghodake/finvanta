package com.finvanta.batch;

import com.finvanta.domain.entity.BatchJob;
import com.finvanta.repository.BatchJobRepository;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Batch History Query Service per Finacle EOD_INQUIRY / Temenos COB_HISTORY.
 *
 * <p>Extracted from the deprecated {@code com.finvanta.legacy.BatchService} so
 * that {@code BatchController} (and any future EOD dashboard) can read batch
 * history without depending on the legacy package. The ArchUnit guard
 * {@code legacyPackage_notDependedOnFromProduction} enforces this boundary.
 *
 * <p>Read-only service -- no mutations, no financial operations.
 */
@Service
public class BatchHistoryService {

    private final BatchJobRepository batchJobRepository;

    public BatchHistoryService(BatchJobRepository batchJobRepository) {
        this.batchJobRepository = batchJobRepository;
    }

    /** Returns all batch jobs for the current tenant, most recent first. */
    @Transactional(readOnly = true)
    public List<BatchJob> getBatchHistory() {
        return batchJobRepository.findByTenantIdOrderByCreatedAtDesc(
                TenantContext.getCurrentTenant());
    }

    /** Returns the EOD batch job for a specific business date, or null. */
    @Transactional(readOnly = true)
    public BatchJob getBatchJobByDate(LocalDate businessDate) {
        return batchJobRepository
                .findByTenantIdAndJobNameAndBusinessDate(
                        TenantContext.getCurrentTenant(), "EOD_BATCH", businessDate)
                .orElse(null);
    }
}
