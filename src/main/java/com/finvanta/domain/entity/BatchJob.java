package com.finvanta.domain.entity;

import com.finvanta.domain.enums.BatchStatus;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "batch_jobs",
        indexes = {
            @Index(name = "idx_batch_tenant_date", columnList = "tenant_id, business_date"),
            @Index(name = "idx_batch_status", columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class BatchJob extends BaseEntity {

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BatchStatus status = BatchStatus.PENDING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "total_records")
    private int totalRecords;

    @Column(name = "processed_records")
    private int processedRecords;

    @Column(name = "failed_records")
    private int failedRecords;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "step_name", length = 100)
    private String stepName;

    @Column(name = "initiated_by", length = 100)
    private String initiatedBy;
}
