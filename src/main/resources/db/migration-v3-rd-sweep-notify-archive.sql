-- ============================================================
-- Finvanta CBS — Migration V3g: Recurring Deposits, Sweep Config,
-- Notification Delivery, Data Archival Policy.
-- Covers GAP-07, GAP-13, GAP-19, GAP-27.
-- Run AFTER migration-v2-tier1-hardening.sql.
-- ============================================================

-- 1. Recurring Deposits (GAP-07)
-- Per RBI: monthly installment-based term deposit.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='recurring_deposits')
CREATE TABLE recurring_deposits (
    id                      BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id               VARCHAR(20)     NOT NULL,
    rd_account_number       VARCHAR(40)     NOT NULL,
    customer_id             BIGINT          NOT NULL,
    branch_id               BIGINT          NOT NULL,
    branch_code             VARCHAR(20)     NOT NULL,
    installment_amount      DECIMAL(18,2)   NOT NULL,
    total_installments      INT             NOT NULL,
    paid_installments       INT             NOT NULL DEFAULT 0,
    missed_installments     INT             NOT NULL DEFAULT 0,
    next_installment_date   DATE,
    last_installment_date   DATE,
    cumulative_deposit      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency_code           VARCHAR(3)      NOT NULL DEFAULT 'INR',
    interest_rate           DECIMAL(8,4)    NOT NULL,
    accrued_interest        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total_interest_credited DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    ytd_interest_credited   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    ytd_tds_deducted        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    last_accrual_date       DATE,
    penalty_rate            DECIMAL(8,4)    DEFAULT 2.0000,
    premature_penalty_rate  DECIMAL(8,4)    DEFAULT 1.0000,
    booking_date            DATE            NOT NULL,
    maturity_date           DATE            NOT NULL,
    closure_date            DATE,
    status                  VARCHAR(25)     NOT NULL DEFAULT 'ACTIVE',
    linked_account_number   VARCHAR(40)     NOT NULL,
    product_code            VARCHAR(50)     NOT NULL,
    nominee_name            VARCHAR(200),
    nominee_relationship    VARCHAR(30),
    booking_journal_id      BIGINT,
    closure_journal_id      BIGINT,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at              DATETIME2,
    created_by              VARCHAR(100),
    updated_by              VARCHAR(100),
    CONSTRAINT uq_rd_tenant_rdno UNIQUE (tenant_id, rd_account_number)
);
GO
CREATE INDEX idx_rd_tenant_customer ON recurring_deposits (tenant_id, customer_id);
CREATE INDEX idx_rd_tenant_status ON recurring_deposits (tenant_id, status);
CREATE INDEX idx_rd_next_installment ON recurring_deposits (tenant_id, next_installment_date, status);
GO

-- 2. Sweep Configuration (GAP-13)
-- Per Finacle SWEEP_MASTER: auto-sweep excess CASA to FD.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='sweep_configurations')
CREATE TABLE sweep_configurations (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    account_id          BIGINT          NOT NULL,
    sweep_type          VARCHAR(20)     NOT NULL,
    -- SWEEP_OUT (CASA→FD when above threshold)
    -- SWEEP_IN  (FD→CASA when below minimum)
    -- AUTO_SWEEP (both directions)
    threshold_amount    DECIMAL(18,2)   NOT NULL,
    minimum_balance     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    sweep_unit          DECIMAL(18,2)   NOT NULL DEFAULT 1000.00,
    -- Sweep in multiples of this amount
    target_product_code VARCHAR(50),
    -- FD product to create for sweep-out
    target_tenure_days  INT             DEFAULT 365,
    is_active           BIT             NOT NULL DEFAULT 1,
    last_sweep_date     DATE,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100)
);
GO
CREATE INDEX idx_sweep_account ON sweep_configurations (tenant_id, account_id, is_active);
GO

-- 3. Notification Delivery Log (GAP-19)
-- Per RBI: transaction alerts are mandatory.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='notification_delivery_log')
CREATE TABLE notification_delivery_log (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    notification_id     BIGINT,
    channel             VARCHAR(20)     NOT NULL,
    -- SMS, EMAIL, PUSH, WHATSAPP
    recipient           VARCHAR(200)    NOT NULL,
    subject             VARCHAR(500),
    message_body        NVARCHAR(2000)  NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, SENT, DELIVERED, FAILED, BOUNCED
    gateway_ref         VARCHAR(100),
    gateway_response    VARCHAR(500),
    sent_at             DATETIME2,
    delivered_at        DATETIME2,
    retry_count         INT             NOT NULL DEFAULT 0,
    last_error          VARCHAR(500),
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE()
);
GO
CREATE INDEX idx_notif_tenant_status ON notification_delivery_log (tenant_id, status);
CREATE INDEX idx_notif_channel ON notification_delivery_log (tenant_id, channel, status);
GO

-- 4. Data Archival Policy (GAP-27)
-- Per RBI: minimum 8-year retention for financial data.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='data_archival_policies')
CREATE TABLE data_archival_policies (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    table_name          VARCHAR(100)    NOT NULL,
    retention_years     INT             NOT NULL DEFAULT 8,
    archive_strategy    VARCHAR(30)     NOT NULL DEFAULT 'MOVE_TO_ARCHIVE',
    -- MOVE_TO_ARCHIVE, COMPRESS, PARTITION, PURGE_AFTER_ARCHIVE
    archive_table_name  VARCHAR(100),
    last_archive_date   DATE,
    last_archive_count  INT,
    is_active           BIT             NOT NULL DEFAULT 1,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    CONSTRAINT uq_archival_policy UNIQUE (tenant_id, table_name)
);
GO

-- Seed default archival policies per RBI data retention guidelines
INSERT INTO data_archival_policies (tenant_id, table_name, retention_years, archive_strategy, archive_table_name, is_active, created_at, created_by)
SELECT 'DEFAULT', 'ledger_entries', 8, 'PARTITION', 'ledger_entries_archive', 1, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_archival_policies WHERE tenant_id='DEFAULT' AND table_name='ledger_entries');

INSERT INTO data_archival_policies (tenant_id, table_name, retention_years, archive_strategy, archive_table_name, is_active, created_at, created_by)
SELECT 'DEFAULT', 'audit_logs', 8, 'PARTITION', 'audit_logs_archive', 1, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_archival_policies WHERE tenant_id='DEFAULT' AND table_name='audit_logs');

INSERT INTO data_archival_policies (tenant_id, table_name, retention_years, archive_strategy, archive_table_name, is_active, created_at, created_by)
SELECT 'DEFAULT', 'journal_entries', 8, 'PARTITION', 'journal_entries_archive', 1, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_archival_policies WHERE tenant_id='DEFAULT' AND table_name='journal_entries');

INSERT INTO data_archival_policies (tenant_id, table_name, retention_years, archive_strategy, archive_table_name, is_active, created_at, created_by)
SELECT 'DEFAULT', 'deposit_transactions', 8, 'MOVE_TO_ARCHIVE', 'deposit_transactions_archive', 1, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_archival_policies WHERE tenant_id='DEFAULT' AND table_name='deposit_transactions');

INSERT INTO data_archival_policies (tenant_id, table_name, retention_years, archive_strategy, archive_table_name, is_active, created_at, created_by)
SELECT 'DEFAULT', 'loan_transactions', 8, 'MOVE_TO_ARCHIVE', 'loan_transactions_archive', 1, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_archival_policies WHERE tenant_id='DEFAULT' AND table_name='loan_transactions');
