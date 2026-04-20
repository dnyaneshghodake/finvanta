-- ============================================================
-- Finvanta CBS — Migration V3b: AML/CFT Tables
-- Per PMLA 2002, RBI Master Direction on KYC 2016 Sections 28-32,
-- FIU-IND Reporting Standards.
-- Run AFTER migration-v2-tier1-hardening.sql.
-- ============================================================

-- 1. Suspicious Transaction Reports (STR)
-- Per RBI KYC MD Section 29: report to FIU-IND within 7 days.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='aml_str_reports')
CREATE TABLE aml_str_reports (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    str_reference       VARCHAR(40)     NOT NULL,
    customer_id         BIGINT          NOT NULL,
    account_reference   VARCHAR(40),
    detection_date      DATE            NOT NULL,
    report_date         DATE,
    filing_date         DATE,
    fiu_acknowledgement VARCHAR(100),
    str_category        VARCHAR(50)     NOT NULL,
    suspicious_amount   DECIMAL(18,2),
    currency_code       VARCHAR(3)      NOT NULL DEFAULT 'INR',
    detection_method    VARCHAR(30)     NOT NULL,
    rule_id             VARCHAR(50),
    risk_score          INT,
    narrative           NVARCHAR(4000)  NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    reviewed_by         VARCHAR(100),
    reviewed_at         DATETIME2,
    approved_by         VARCHAR(100),
    approved_at         DATETIME2,
    related_ctr_id      BIGINT,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_str_ref UNIQUE (tenant_id, str_reference)
);
GO
CREATE INDEX idx_str_tenant_status ON aml_str_reports (tenant_id, status);
CREATE INDEX idx_str_tenant_customer ON aml_str_reports (tenant_id, customer_id);
CREATE INDEX idx_str_detection_date ON aml_str_reports (tenant_id, detection_date);
GO

-- 2. Cash Transaction Reports (CTR) — monthly batch to FIU-IND
-- Per RBI KYC MD Section 28(2): cash txns >= 10L reported monthly.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='aml_ctr_reports')
CREATE TABLE aml_ctr_reports (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    ctr_reference       VARCHAR(40)     NOT NULL,
    reporting_month     DATE            NOT NULL,
    customer_id         BIGINT          NOT NULL,
    account_reference   VARCHAR(40)     NOT NULL,
    transaction_ref     VARCHAR(40),
    transaction_date    DATE            NOT NULL,
    transaction_type    VARCHAR(30)     NOT NULL,
    amount              DECIMAL(18,2)   NOT NULL,
    currency_code       VARCHAR(3)      NOT NULL DEFAULT 'INR',
    branch_code         VARCHAR(20),
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    batch_id            BIGINT,
    filing_date         DATE,
    fiu_acknowledgement VARCHAR(100),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    CONSTRAINT uq_ctr_ref UNIQUE (tenant_id, ctr_reference)
);
GO
CREATE INDEX idx_ctr_tenant_month ON aml_ctr_reports (tenant_id, reporting_month, status);
CREATE INDEX idx_ctr_tenant_customer ON aml_ctr_reports (tenant_id, customer_id);
GO

-- 3. AML Risk Scoring — customer-level per RBI KYC MD Section 16.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='aml_risk_score')
    ALTER TABLE customers ADD aml_risk_score INT DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='aml_risk_category')
    ALTER TABLE customers ADD aml_risk_category VARCHAR(20) DEFAULT 'LOW';
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='aml_last_review_date')
    ALTER TABLE customers ADD aml_last_review_date DATE;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='is_pep')
    ALTER TABLE customers ADD is_pep BIT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='pep_category')
    ALTER TABLE customers ADD pep_category VARCHAR(50);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='sanctions_screened_at')
    ALTER TABLE customers ADD sanctions_screened_at DATETIME2;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='sanctions_clear')
    ALTER TABLE customers ADD sanctions_clear BIT NOT NULL DEFAULT 1;
GO

-- 4. AML Transaction Monitoring Rules
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='aml_monitoring_rules')
CREATE TABLE aml_monitoring_rules (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    rule_code           VARCHAR(50)     NOT NULL,
    rule_name           VARCHAR(200)    NOT NULL,
    rule_category       VARCHAR(50)     NOT NULL,
    description         NVARCHAR(1000),
    threshold_amount    DECIMAL(18,2),
    threshold_count     INT,
    time_window_hours   INT,
    risk_score_impact   INT             NOT NULL DEFAULT 10,
    is_active           BIT             NOT NULL DEFAULT 1,
    auto_str            BIT             NOT NULL DEFAULT 0,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_aml_rule UNIQUE (tenant_id, rule_code)
);
GO

-- 5. AML Alert Queue — triggered by monitoring rules
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='aml_alerts')
CREATE TABLE aml_alerts (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    alert_reference     VARCHAR(40)     NOT NULL,
    rule_id             BIGINT          NOT NULL,
    customer_id         BIGINT          NOT NULL,
    account_reference   VARCHAR(40),
    alert_date          DATETIME2       NOT NULL DEFAULT GETDATE(),
    alert_category      VARCHAR(50)     NOT NULL,
    risk_score          INT             NOT NULL,
    details             NVARCHAR(2000),
    status              VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    assigned_to         VARCHAR(100),
    resolution          NVARCHAR(1000),
    resolved_by         VARCHAR(100),
    resolved_at         DATETIME2,
    str_id              BIGINT,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    CONSTRAINT uq_aml_alert_ref UNIQUE (tenant_id, alert_reference)
);
GO
CREATE INDEX idx_aml_alert_status ON aml_alerts (tenant_id, status);
CREATE INDEX idx_aml_alert_customer ON aml_alerts (tenant_id, customer_id);
GO
