-- ============================================================
-- Finvanta CBS — Migration V2: Tier-1 Hardening
-- Applies to SQL Server production deployments.
-- Run AFTER ddl-sqlserver.sql (base schema).
--
-- Adds: new columns on existing tables + 10 new tables.
-- All IF NOT EXISTS guarded — safe to re-run (idempotent).
-- ============================================================

-- 1. tenants — RBI regulatory fields
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='rbi_bank_code')
    ALTER TABLE tenants ADD rbi_bank_code VARCHAR(10);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='ifsc_prefix')
    ALTER TABLE tenants ADD ifsc_prefix VARCHAR(4);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='license_number')
    ALTER TABLE tenants ADD license_number VARCHAR(50);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='license_expiry')
    ALTER TABLE tenants ADD license_expiry DATE;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='regulatory_category')
    ALTER TABLE tenants ADD regulatory_category VARCHAR(20);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='country_code')
    ALTER TABLE tenants ADD country_code VARCHAR(2);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='base_currency')
    ALTER TABLE tenants ADD base_currency VARCHAR(3);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='timezone')
    ALTER TABLE tenants ADD timezone VARCHAR(50);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='fiscal_year_start_month')
    ALTER TABLE tenants ADD fiscal_year_start_month INT NOT NULL DEFAULT 4;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='crr_percentage')
    ALTER TABLE tenants ADD crr_percentage DECIMAL(8,4) DEFAULT 4.5000;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='slr_percentage')
    ALTER TABLE tenants ADD slr_percentage DECIMAL(8,4) DEFAULT 18.0000;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='tier1_capital_base')
    ALTER TABLE tenants ADD tier1_capital_base DECIMAL(18,2) DEFAULT 0.00;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='business_day_policy')
    ALTER TABLE tenants ADD business_day_policy VARCHAR(20) NOT NULL DEFAULT 'MON_TO_SAT';
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='value_date_back_days')
    ALTER TABLE tenants ADD value_date_back_days INT NOT NULL DEFAULT 2;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='value_date_forward_days')
    ALTER TABLE tenants ADD value_date_forward_days INT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='incorporation_date')
    ALTER TABLE tenants ADD incorporation_date DATE;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='updated_at')
    ALTER TABLE tenants ADD updated_at DATETIME2;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='tenants' AND COLUMN_NAME='updated_by')
    ALTER TABLE tenants ADD updated_by VARCHAR(100);
GO

-- 2. journal_entries — branch + voucher + txnRef
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='journal_entries' AND COLUMN_NAME='branch_id')
    ALTER TABLE journal_entries ADD branch_id BIGINT;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='journal_entries' AND COLUMN_NAME='branch_code')
    ALTER TABLE journal_entries ADD branch_code VARCHAR(20);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='journal_entries' AND COLUMN_NAME='voucher_number')
    ALTER TABLE journal_entries ADD voucher_number VARCHAR(50);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='journal_entries' AND COLUMN_NAME='transaction_ref')
    ALTER TABLE journal_entries ADD transaction_ref VARCHAR(40);
GO

-- 3. ledger_entries — branch
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='ledger_entries' AND COLUMN_NAME='branch_id')
    ALTER TABLE ledger_entries ADD branch_id BIGINT;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='ledger_entries' AND COLUMN_NAME='branch_code')
    ALTER TABLE ledger_entries ADD branch_code VARCHAR(20);
GO

-- 4. deposit_transactions — balance_before + branch
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='deposit_transactions' AND COLUMN_NAME='balance_before')
    ALTER TABLE deposit_transactions ADD balance_before DECIMAL(18,2);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='deposit_transactions' AND COLUMN_NAME='branch_id')
    ALTER TABLE deposit_transactions ADD branch_id BIGINT;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='deposit_transactions' AND COLUMN_NAME='branch_code')
    ALTER TABLE deposit_transactions ADD branch_code VARCHAR(20);
GO

-- 5. business_calendar — branch-scoped + EOD running
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='business_calendar' AND COLUMN_NAME='branch_id')
    ALTER TABLE business_calendar ADD branch_id BIGINT;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='business_calendar' AND COLUMN_NAME='branch_code')
    ALTER TABLE business_calendar ADD branch_code VARCHAR(20);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='business_calendar' AND COLUMN_NAME='is_eod_running')
    ALTER TABLE business_calendar ADD is_eod_running BIT NOT NULL DEFAULT 0;
GO

-- 6. gl_master — period close
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='gl_master' AND COLUMN_NAME='last_period_close_date')
    ALTER TABLE gl_master ADD last_period_close_date DATE;
GO

-- 7. product_master — tiering + lifecycle
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='product_master' AND COLUMN_NAME='interest_tiering_enabled')
    ALTER TABLE product_master ADD interest_tiering_enabled BIT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='product_master' AND COLUMN_NAME='interest_tiering_json')
    ALTER TABLE product_master ADD interest_tiering_json NVARCHAR(MAX);
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='product_master' AND COLUMN_NAME='product_status')
    ALTER TABLE product_master ADD product_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='product_master' AND COLUMN_NAME='config_version')
    ALTER TABLE product_master ADD config_version INT NOT NULL DEFAULT 1;
GO

-- ============================================================
-- NEW TABLES
-- ============================================================

-- GL Branch Balances
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='gl_branch_balances')
CREATE TABLE gl_branch_balances (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id VARCHAR(20) NOT NULL,
    branch_id BIGINT NOT NULL,
    gl_code VARCHAR(20) NOT NULL,
    gl_name VARCHAR(200),
    debit_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    credit_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    last_period_close_date DATE,
    opening_debit_balance DECIMAL(18,2) DEFAULT 0.00,
    opening_credit_balance DECIMAL(18,2) DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    updated_at DATETIME2, created_by VARCHAR(100), updated_by VARCHAR(100),
    CONSTRAINT fk_glbb_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
GO
CREATE UNIQUE INDEX idx_glbb_tenant_branch_gl ON gl_branch_balances (tenant_id, branch_id, gl_code);
CREATE INDEX idx_glbb_tenant_gl ON gl_branch_balances (tenant_id, gl_code);
GO

-- Tenant Ledger State
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='tenant_ledger_state')
CREATE TABLE tenant_ledger_state (
    tenant_id VARCHAR(50) NOT NULL PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    last_hash VARCHAR(128) NOT NULL DEFAULT 'GENESIS',
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME2 NOT NULL DEFAULT GETDATE()
);
GO

-- Idempotency Registry
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='idempotency_registry')
CREATE TABLE idempotency_registry (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    transaction_ref VARCHAR(40) NOT NULL,
    voucher_number VARCHAR(50),
    journal_entry_id BIGINT,
    status VARCHAR(30) NOT NULL,
    source_module VARCHAR(50),
    created_at DATETIME2 NOT NULL
);
GO
CREATE UNIQUE INDEX idx_idem_tenant_key ON idempotency_registry (tenant_id, idempotency_key);
CREATE INDEX idx_idem_tenant_txnref ON idempotency_registry (tenant_id, transaction_ref);
GO

-- Transaction Outbox
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='transaction_outbox')
CREATE TABLE transaction_outbox (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    transaction_ref VARCHAR(40) NOT NULL,
    voucher_number VARCHAR(50),
    journal_entry_id BIGINT,
    source_module VARCHAR(50),
    transaction_type VARCHAR(30),
    account_reference VARCHAR(40),
    amount DECIMAL(18,2),
    branch_code VARCHAR(20),
    value_date DATE,
    rbi_flags INT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    published_at DATETIME2,
    created_at DATETIME2 NOT NULL
);
GO
CREATE INDEX idx_outbox_tenant_status ON transaction_outbox (tenant_id, status);
CREATE INDEX idx_outbox_txnref ON transaction_outbox (tenant_id, transaction_ref);
GO

-- Daily Balance Snapshots (CASA min-balance interest)
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='daily_balance_snapshots')
CREATE TABLE daily_balance_snapshots (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id VARCHAR(20) NOT NULL,
    account_id BIGINT NOT NULL,
    snapshot_date DATE NOT NULL,
    closing_balance DECIMAL(18,2) NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT fk_dbs_account FOREIGN KEY (account_id) REFERENCES deposit_accounts(id)
);
GO
CREATE UNIQUE INDEX idx_dbs_tenant_acct_date ON daily_balance_snapshots (tenant_id, account_id, snapshot_date);
GO

-- Charge Definitions
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='charge_definitions')
CREATE TABLE charge_definitions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    charge_name VARCHAR(200) NOT NULL,
    charge_type VARCHAR(20) NOT NULL,
    charge_amount DECIMAL(18,2),
    charge_percentage DECIMAL(8,4),
    min_charge DECIMAL(18,2),
    max_charge DECIMAL(18,2),
    gst_applicable BIT NOT NULL DEFAULT 1,
    gl_fee_income VARCHAR(20),
    is_active BIT NOT NULL DEFAULT 1,
    is_waivable BIT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    updated_at DATETIME2, created_by VARCHAR(100), updated_by VARCHAR(100)
);
GO

-- NOTE: Additional tables (fixed_deposits, clearing_cycles, settlement_batches,
-- notification_logs, notification_templates, customer_documents, permissions,
-- role_permissions, loan_balance_snapshots, charge_transactions) should be
-- added in subsequent migration files as those modules are production-hardened.
-- This migration covers the critical tables needed for the transaction engine,
-- GL posting pipeline, and CASA module to function on SQL Server.