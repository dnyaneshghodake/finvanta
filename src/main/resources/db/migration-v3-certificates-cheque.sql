-- ============================================================
-- Finvanta CBS — Migration V3f: Customer Certificates, Form 15G/15H,
-- Cheque Management, and FATCA/CRS Tables.
-- Covers GAP-07 (partial), GAP-08, GAP-10, GAP-11, GAP-12.
-- Run AFTER migration-v2-tier1-hardening.sql.
-- ============================================================

-- 1. Form 15G/15H — TDS Exemption per IT Act Section 197A
-- Per IT Act: customers with income below taxable limit can submit
-- Form 15G (below 60 years) or 15H (60+ years) to avoid TDS deduction.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='tds_exemption_forms')
CREATE TABLE tds_exemption_forms (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    form_reference      VARCHAR(40)     NOT NULL,
    customer_id         BIGINT          NOT NULL,
    account_id          BIGINT          NOT NULL,
    form_type           VARCHAR(5)      NOT NULL,
    -- 15G (below 60 years), 15H (60+ years)
    financial_year      VARCHAR(10)     NOT NULL,
    -- Format: 2024-25
    pan_number          VARCHAR(100),
    estimated_income    DECIMAL(18,2)   NOT NULL,
    estimated_interest  DECIMAL(18,2)   NOT NULL,
    declaration_date    DATE            NOT NULL,
    valid_from          DATE            NOT NULL,
    valid_to            DATE            NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE, EXPIRED, REVOKED
    verified_by         VARCHAR(100),
    verified_at         DATETIME2,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_tds_form_ref UNIQUE (tenant_id, form_reference)
);
GO
CREATE INDEX idx_tds_form_customer ON tds_exemption_forms (tenant_id, customer_id, financial_year);
CREATE INDEX idx_tds_form_account ON tds_exemption_forms (tenant_id, account_id, status);
GO

-- 2. Customer Certificate Requests — Interest cert, NOC, balance confirmation
-- Per RBI Fair Practices Code: banks must issue certificates on request.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='certificate_requests')
CREATE TABLE certificate_requests (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    request_reference   VARCHAR(40)     NOT NULL,
    customer_id         BIGINT          NOT NULL,
    account_id          BIGINT,
    loan_account_id     BIGINT,
    certificate_type    VARCHAR(30)     NOT NULL,
    -- INTEREST_CERTIFICATE_16A, TDS_CERTIFICATE, BALANCE_CONFIRMATION,
    -- LOAN_CLOSURE_NOC, SOLVENCY_CERTIFICATE, ACCOUNT_STATEMENT_PDF
    financial_year      VARCHAR(10),
    period_from         DATE,
    period_to           DATE,
    status              VARCHAR(20)     NOT NULL DEFAULT 'REQUESTED',
    -- REQUESTED, PROCESSING, GENERATED, ISSUED, REJECTED
    generated_at        DATETIME2,
    issued_at           DATETIME2,
    issued_by           VARCHAR(100),
    document_path       VARCHAR(500),
    rejection_reason    VARCHAR(500),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_cert_ref UNIQUE (tenant_id, request_reference)
);
GO
CREATE INDEX idx_cert_customer ON certificate_requests (tenant_id, customer_id);
CREATE INDEX idx_cert_status ON certificate_requests (tenant_id, status);
GO

-- 3. Cheque Book Management — per RBI CTS-2010 Grid Standards
-- Per RBI: Positive Pay mandatory for cheques >= 50,000
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='cheque_books')
CREATE TABLE cheque_books (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    account_id          BIGINT          NOT NULL,
    series_start        VARCHAR(10)     NOT NULL,
    series_end          VARCHAR(10)     NOT NULL,
    total_leaves        INT             NOT NULL,
    used_leaves         INT             NOT NULL DEFAULT 0,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE, EXHAUSTED, CANCELLED, LOST
    issued_date         DATE            NOT NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100)
);
GO
CREATE INDEX idx_chqbook_account ON cheque_books (tenant_id, account_id);
GO

-- 4. Stop Payment Instructions
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='stop_payment_instructions')
CREATE TABLE stop_payment_instructions (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    account_id          BIGINT          NOT NULL,
    cheque_number_from  VARCHAR(10)     NOT NULL,
    cheque_number_to    VARCHAR(10)     NOT NULL,
    reason              VARCHAR(500)    NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE, EXPIRED, REVOKED
    effective_date      DATE            NOT NULL,
    expiry_date         DATE,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100)
);
GO
CREATE INDEX idx_stop_account ON stop_payment_instructions (tenant_id, account_id, status);
GO

-- 5. Positive Pay Confirmations — per RBI mandate for cheques >= 50,000
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='positive_pay_entries')
CREATE TABLE positive_pay_entries (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    account_id          BIGINT          NOT NULL,
    cheque_number       VARCHAR(10)     NOT NULL,
    cheque_date         DATE            NOT NULL,
    payee_name          VARCHAR(200)    NOT NULL,
    amount              DECIMAL(18,2)   NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'REGISTERED',
    -- REGISTERED, MATCHED, MISMATCHED, EXPIRED
    registered_date     DATETIME2       NOT NULL DEFAULT GETDATE(),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100)
);
GO
CREATE INDEX idx_posipay_account ON positive_pay_entries (tenant_id, account_id, cheque_number);
GO

-- 6. FATCA/CRS Self-Certification — per RBI Master Direction on FATCA 2016
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='fatca_status')
    ALTER TABLE customers ADD fatca_status VARCHAR(20) DEFAULT 'NOT_CERTIFIED';
    -- NOT_CERTIFIED, CERTIFIED_NON_US, CERTIFIED_US_PERSON, INDICIA_FOUND
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='fatca_certification_date')
    ALTER TABLE customers ADD fatca_certification_date DATE;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='crs_country_1')
    ALTER TABLE customers ADD crs_country_1 VARCHAR(2);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='crs_tin_1')
    ALTER TABLE customers ADD crs_tin_1 VARCHAR(50);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='crs_country_2')
    ALTER TABLE customers ADD crs_country_2 VARCHAR(2);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='customers' AND COLUMN_NAME='crs_tin_2')
    ALTER TABLE customers ADD crs_tin_2 VARCHAR(50);
GO
