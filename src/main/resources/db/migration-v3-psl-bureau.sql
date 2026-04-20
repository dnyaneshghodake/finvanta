-- ============================================================
-- Finvanta CBS — Migration V3c: PSL Classification + Credit Bureau
-- Per RBI Master Direction on PSL 2020 and CICRA 2005.
-- Run AFTER migration-v2-tier1-hardening.sql.
-- ============================================================

-- 1. Priority Sector Lending — loan account classification
-- Per RBI PSL MD: 40% of ANBC must be in priority sectors.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='loan_accounts' AND COLUMN_NAME='psl_category')
    ALTER TABLE loan_accounts ADD psl_category VARCHAR(30);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='loan_accounts' AND COLUMN_NAME='psl_sub_category')
    ALTER TABLE loan_accounts ADD psl_sub_category VARCHAR(50);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='loan_accounts' AND COLUMN_NAME='psl_certified')
    ALTER TABLE loan_accounts ADD psl_certified BIT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='loan_accounts' AND COLUMN_NAME='psl_certified_date')
    ALTER TABLE loan_accounts ADD psl_certified_date DATE;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='loan_accounts' AND COLUMN_NAME='weaker_section')
    ALTER TABLE loan_accounts ADD weaker_section BIT NOT NULL DEFAULT 0;
GO

-- 2. Credit Bureau Inquiries
-- Per CICRA 2005: mandatory check before any credit facility.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='credit_bureau_inquiries')
CREATE TABLE credit_bureau_inquiries (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    inquiry_reference   VARCHAR(40)     NOT NULL,
    customer_id         BIGINT          NOT NULL,
    application_id      BIGINT,
    bureau_name         VARCHAR(30)     NOT NULL,
    inquiry_date        DATETIME2       NOT NULL,
    inquiry_purpose     VARCHAR(30)     NOT NULL,
    credit_score        INT,
    score_version       VARCHAR(20),
    report_data         NVARCHAR(MAX),
    response_code       VARCHAR(20),
    dpd_max_last_12m    INT,
    dpd_max_last_24m    INT,
    active_accounts     INT,
    overdue_accounts    INT,
    total_outstanding   DECIMAL(18,2),
    total_overdue       DECIMAL(18,2),
    enquiry_count_6m    INT,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    error_message       VARCHAR(500),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    CONSTRAINT uq_cbi_ref UNIQUE (tenant_id, inquiry_reference)
);
GO
CREATE INDEX idx_cbi_customer ON credit_bureau_inquiries (tenant_id, customer_id);
CREATE INDEX idx_cbi_app ON credit_bureau_inquiries (tenant_id, application_id);
GO

-- 3. Credit Bureau Monthly Submissions
-- Per CICRA 2005: monthly data submission to all 4 bureaus.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='credit_bureau_submissions')
CREATE TABLE credit_bureau_submissions (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    submission_ref      VARCHAR(40)     NOT NULL,
    bureau_name         VARCHAR(30)     NOT NULL,
    reporting_month     DATE            NOT NULL,
    total_records       INT             NOT NULL DEFAULT 0,
    accepted_records    INT,
    rejected_records    INT,
    file_name           VARCHAR(200),
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    submitted_at        DATETIME2,
    acknowledgement     VARCHAR(200),
    error_details       NVARCHAR(2000),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    CONSTRAINT uq_cbs_ref UNIQUE (tenant_id, submission_ref)
);
GO
CREATE INDEX idx_cbs_month ON credit_bureau_submissions (tenant_id, reporting_month);
GO

-- 4. Customer credit score cache (latest per bureau)
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='cibil_score')
    ALTER TABLE customers ADD cibil_score INT;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='cibil_score_date')
    ALTER TABLE customers ADD cibil_score_date DATE;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='bureau_check_required')
    ALTER TABLE customers ADD bureau_check_required BIT NOT NULL DEFAULT 1;
GO
