package com.finvanta.domain.enums;

/**
 * EOD/BOD batch job status per Finacle/Temenos batch processing standards.
 *
 * CBS batch lifecycle:
 *   PENDING → RUNNING → COMPLETED / PARTIALLY_COMPLETED / FAILED
 *
 * Per Finacle EOD guidelines:
 * - PARTIALLY_COMPLETED indicates some accounts failed but EOD proceeded
 *   (failed accounts are logged for manual intervention)
 * - FAILED indicates a system-level failure that prevented EOD completion
 * - ROLLED_BACK indicates the batch was explicitly reversed by admin
 */
public enum BatchStatus {
    PENDING, // Scheduled but not yet started
    RUNNING, // Currently executing EOD steps
    COMPLETED, // All accounts processed successfully
    FAILED, // System-level failure, EOD not completed
    PARTIALLY_COMPLETED, // Some accounts failed, EOD completed with errors
    ROLLED_BACK // Batch reversed by administrator
}
