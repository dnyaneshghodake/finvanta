-- ============================================================
-- Finvanta CBS — Migration V3h: Feature Flags
-- Per RBI IT Governance Direction 2023 and Finacle BANK_PARAM.
--
-- Feature flags enable runtime enable/disable of:
-- - Payment rails (NEFT, RTGS, IMPS, UPI) per RBI directive
-- - Product modules (Gold Loan, Education Loan) per license
-- - System features (ISO20022, NEFT_24x7) per readiness
-- - Tenant-specific overrides for multi-bank deployments
--
-- Run AFTER migration-v2-tier1-hardening.sql.
-- ============================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='feature_flags')
CREATE TABLE feature_flags (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    flag_code           VARCHAR(50)     NOT NULL,
    flag_name           VARCHAR(200)    NOT NULL,
    category            VARCHAR(30)     NOT NULL,
    -- PAYMENT_RAIL, PRODUCT_MODULE, SYSTEM_FEATURE, UI_FEATURE
    is_enabled          BIT             NOT NULL DEFAULT 0,
    description         NVARCHAR(500),
    enabled_by          VARCHAR(100),
    enabled_at          DATETIME2,
    disabled_by         VARCHAR(100),
    disabled_at         DATETIME2,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_feature_flag UNIQUE (tenant_id, flag_code)
);
GO
CREATE INDEX idx_ff_tenant_category ON feature_flags (tenant_id, category, is_enabled);
GO

-- Seed default feature flags per RBI payment system availability
INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'NEFT_ENABLED', 'NEFT Payment Rail', 'PAYMENT_RAIL', 1,
    'National Electronic Funds Transfer — batch settlement via RBI. Per RBI: mandatory for all scheduled banks.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='NEFT_ENABLED');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'RTGS_ENABLED', 'RTGS Payment Rail', 'PAYMENT_RAIL', 1,
    'Real Time Gross Settlement — real-time high-value transfers via RBI.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='RTGS_ENABLED');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'IMPS_ENABLED', 'IMPS Payment Rail', 'PAYMENT_RAIL', 1,
    'Immediate Payment Service — 24x7 instant transfers via NPCI.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='IMPS_ENABLED');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'UPI_ENABLED', 'UPI Payment Rail', 'PAYMENT_RAIL', 1,
    'Unified Payments Interface — mobile payments via NPCI.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='UPI_ENABLED');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'NEFT_24X7', 'NEFT 24x7 Mode', 'SYSTEM_FEATURE', 0,
    'NEFT round-the-clock settlement per RBI Dec 2019 directive. Requires SFMS upgrade.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='NEFT_24X7');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'ISO20022_ENABLED', 'ISO 20022 Messaging', 'SYSTEM_FEATURE', 0,
    'ISO 20022 XML messaging format for NEFT/RTGS. Per RBI migration timeline.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='ISO20022_ENABLED');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'RECURRING_DEPOSIT', 'Recurring Deposit Module', 'PRODUCT_MODULE', 1,
    'RD product booking, installment processing, maturity.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='RECURRING_DEPOSIT');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'GOLD_LOAN', 'Gold Loan Module', 'PRODUCT_MODULE', 1,
    'Gold loan origination and servicing.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='GOLD_LOAN');

INSERT INTO feature_flags (tenant_id, flag_code, flag_name, category, is_enabled, description, created_at, created_by)
SELECT 'DEFAULT', 'POSITIVE_PAY', 'Positive Pay System', 'SYSTEM_FEATURE', 1,
    'Per RBI mandate: cheque confirmation for amounts >= 50,000.',
    CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE tenant_id='DEFAULT' AND flag_code='POSITIVE_PAY');
