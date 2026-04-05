-- ============================================================
-- Finvanta CBS - SQL Server DDL
-- Tier-1 Core Banking System - RBI Compliant
-- ============================================================

-- 1. TENANTS
CREATE TABLE tenants (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_code     VARCHAR(20)     NOT NULL,
    tenant_name     VARCHAR(200)    NOT NULL,
    license_type    VARCHAR(50),
    is_active       BIT             NOT NULL DEFAULT 1,
    db_schema       VARCHAR(100),
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    created_by      VARCHAR(100),
    CONSTRAINT uq_tenant_code UNIQUE (tenant_code)
);
CREATE INDEX idx_tenant_code ON tenants (tenant_code);

-- 2. BRANCHES
CREATE TABLE branches (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    branch_code     VARCHAR(20)     NOT NULL,
    branch_name     VARCHAR(200)    NOT NULL,
    ifsc_code       VARCHAR(11),
    address         VARCHAR(500),
    city            VARCHAR(100),
    state           VARCHAR(100),
    pin_code        VARCHAR(6),
    is_active       BIT             NOT NULL DEFAULT 1,
    region          VARCHAR(100),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_branch_tenant_code UNIQUE (tenant_id, branch_code)
);
CREATE INDEX idx_branch_tenant_code ON branches (tenant_id, branch_code);
CREATE INDEX idx_branch_tenant_active ON branches (tenant_id, is_active);

-- 3. BUSINESS CALENDAR (Day Control — Finacle/Temenos pattern)
CREATE TABLE business_calendar (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    business_date   DATE            NOT NULL,
    is_holiday      BIT             NOT NULL DEFAULT 0,
    holiday_description VARCHAR(200),
    day_status      VARCHAR(20)     NOT NULL DEFAULT 'NOT_OPENED',
    is_eod_complete BIT             NOT NULL DEFAULT 0,
    is_locked       BIT             NOT NULL DEFAULT 0,
    day_opened_by   VARCHAR(100),
    day_opened_at   DATETIME2,
    day_closed_by   VARCHAR(100),
    day_closed_at   DATETIME2,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_buscal_tenant_date UNIQUE (tenant_id, business_date)
);
CREATE INDEX idx_buscal_tenant_date ON business_calendar (tenant_id, business_date);
CREATE INDEX idx_buscal_day_status ON business_calendar (tenant_id, day_status);

-- 4. CUSTOMERS
CREATE TABLE customers (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    customer_number VARCHAR(40)     NOT NULL,
    first_name      VARCHAR(100)    NOT NULL,
    last_name       VARCHAR(100)    NOT NULL,
    date_of_birth   DATE,
    pan_number      VARCHAR(100),    -- Expanded for AES-256-GCM ciphertext (RBI PII encryption)
    aadhaar_number  VARCHAR(100),   -- Expanded for AES-256-GCM ciphertext (UIDAI/RBI mandate)
    mobile_number   VARCHAR(15),
    email           VARCHAR(200),
    address         VARCHAR(500),
    city            VARCHAR(100),
    state           VARCHAR(100),
    pin_code        VARCHAR(6),
    kyc_verified    BIT             NOT NULL DEFAULT 0,
    kyc_verified_date DATE,
    kyc_verified_by VARCHAR(100),
    cibil_score     INT,
    customer_type   VARCHAR(20),
    is_active       BIT             NOT NULL DEFAULT 1,
    branch_id       BIGINT          NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_cust_tenant_custno UNIQUE (tenant_id, customer_number),
    CONSTRAINT fk_cust_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_cust_tenant_custno ON customers (tenant_id, customer_number);
CREATE INDEX idx_cust_pan ON customers (tenant_id, pan_number);
CREATE INDEX idx_cust_aadhaar ON customers (tenant_id, aadhaar_number);

-- 5. PRODUCT MASTER (Finacle PDDEF / Temenos AA.PRODUCT.CATALOG)
CREATE TABLE product_master (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    product_code    VARCHAR(50)     NOT NULL,
    product_name    VARCHAR(200)    NOT NULL,
    product_category VARCHAR(50)    NOT NULL,
    description     VARCHAR(500),
    currency_code   VARCHAR(3)      NOT NULL DEFAULT 'INR',
    interest_method VARCHAR(30)     NOT NULL DEFAULT 'ACTUAL_365',
    interest_type   VARCHAR(20)     NOT NULL DEFAULT 'FIXED',
    min_interest_rate DECIMAL(8,4),
    max_interest_rate DECIMAL(8,4),
    default_penal_rate DECIMAL(8,4) DEFAULT 2.0000,
    min_loan_amount DECIMAL(18,2),
    max_loan_amount DECIMAL(18,2),
    min_tenure_months INT,
    max_tenure_months INT,
    repayment_frequency VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    -- GL Code Mapping (Product → GL)
    gl_loan_asset       VARCHAR(20) NOT NULL,
    gl_interest_receivable VARCHAR(20) NOT NULL,
    gl_bank_operations  VARCHAR(20) NOT NULL,
    gl_interest_income  VARCHAR(20) NOT NULL,
    gl_fee_income       VARCHAR(20) NOT NULL,
    gl_penal_income     VARCHAR(20) NOT NULL,
    gl_provision_expense VARCHAR(20) NOT NULL,
    gl_provision_npa    VARCHAR(20) NOT NULL,
    gl_write_off_expense VARCHAR(20) NOT NULL,
    gl_interest_suspense VARCHAR(20) NOT NULL,
    is_active       BIT             NOT NULL DEFAULT 1,
    repayment_allocation VARCHAR(30) NOT NULL DEFAULT 'INTEREST_FIRST',
    prepayment_penalty_applicable BIT NOT NULL DEFAULT 0,
    processing_fee_pct DECIMAL(8,4) DEFAULT 0.0000,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_product_tenant_code UNIQUE (tenant_id, product_code)
);
CREATE INDEX idx_product_tenant_code ON product_master (tenant_id, product_code);
CREATE INDEX idx_product_tenant_active ON product_master (tenant_id, is_active);

-- 6. LOAN APPLICATIONS
CREATE TABLE loan_applications (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    application_number VARCHAR(40)  NOT NULL,
    customer_id     BIGINT          NOT NULL,
    branch_id       BIGINT          NOT NULL,
    product_type    VARCHAR(50)     NOT NULL,
    requested_amount DECIMAL(18,2)  NOT NULL,
    approved_amount DECIMAL(18,2),
    interest_rate   DECIMAL(8,4)    NOT NULL,
    tenure_months   INT             NOT NULL,
    purpose         VARCHAR(500),
    status          VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    application_date DATE,
    verified_by     VARCHAR(100),
    verified_date   DATE,
    approved_by     VARCHAR(100),
    approved_date   DATE,
    rejected_by     VARCHAR(100),
    rejected_date   DATE,
    rejection_reason VARCHAR(500),
    remarks         VARCHAR(1000),
    collateral_reference VARCHAR(100),
    risk_category   VARCHAR(20),
    penal_rate      DECIMAL(8,4),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_loanapp_tenant_appno UNIQUE (tenant_id, application_number),
    CONSTRAINT fk_loanapp_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_loanapp_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_loanapp_tenant_appno ON loan_applications (tenant_id, application_number);
CREATE INDEX idx_loanapp_status ON loan_applications (tenant_id, status);
CREATE INDEX idx_loanapp_customer ON loan_applications (tenant_id, customer_id);

-- 6. LOAN ACCOUNTS
CREATE TABLE loan_accounts (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    account_number  VARCHAR(40)     NOT NULL,
    application_id  BIGINT          NOT NULL,
    customer_id     BIGINT          NOT NULL,
    branch_id       BIGINT          NOT NULL,
    product_type    VARCHAR(50)     NOT NULL,
    currency_code   VARCHAR(3)      NOT NULL DEFAULT 'INR',  -- ISO 4217 currency code
    sanctioned_amount DECIMAL(18,2) NOT NULL,
    disbursed_amount DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    outstanding_principal DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    outstanding_interest DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    accrued_interest DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    overdue_principal DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    overdue_interest DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    interest_rate   DECIMAL(8,4)    NOT NULL,
    penal_rate      DECIMAL(8,4)    DEFAULT 0.0000,
    emi_amount      DECIMAL(18,2),
    repayment_frequency VARCHAR(20) DEFAULT 'MONTHLY',
    tenure_months   INT             NOT NULL,
    remaining_tenure INT,
    disbursement_date DATE,
    maturity_date   DATE,
    next_emi_date   DATE,
    last_payment_date DATE,
    last_interest_accrual_date DATE,
    status          VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    days_past_due   INT             NOT NULL DEFAULT 0,
    npa_date        DATE,
    npa_classification_date DATE,
    provisioning_amount DECIMAL(18,2) DEFAULT 0.00,
    penal_interest_accrued DECIMAL(18,2) DEFAULT 0.00,
    last_penal_accrual_date DATE,
    collateral_reference VARCHAR(100),
    risk_category   VARCHAR(20),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_loacc_tenant_accno UNIQUE (tenant_id, account_number),
    CONSTRAINT fk_loacc_application FOREIGN KEY (application_id) REFERENCES loan_applications(id),
    CONSTRAINT fk_loacc_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_loacc_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_loacc_tenant_accno ON loan_accounts (tenant_id, account_number);
CREATE INDEX idx_loacc_status ON loan_accounts (tenant_id, status);
CREATE INDEX idx_loacc_customer ON loan_accounts (tenant_id, customer_id);
CREATE INDEX idx_loacc_npa ON loan_accounts (tenant_id, status, days_past_due);

-- 7. LOAN SCHEDULES (Amortization — generated at disbursement)
CREATE TABLE loan_schedules (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    loan_account_id BIGINT          NOT NULL,
    installment_number INT          NOT NULL,
    due_date        DATE            NOT NULL,
    emi_amount      DECIMAL(18,2)   NOT NULL,
    principal_amount DECIMAL(18,2)  NOT NULL,
    interest_amount DECIMAL(18,2)   NOT NULL,
    closing_balance DECIMAL(18,2)   NOT NULL,
    paid_amount     DECIMAL(18,2)   DEFAULT 0.00,
    paid_principal  DECIMAL(18,2)   DEFAULT 0.00,
    paid_interest   DECIMAL(18,2)   DEFAULT 0.00,
    paid_date       DATE,
    status          VARCHAR(20)     NOT NULL DEFAULT 'SCHEDULED',
    penalty_amount  DECIMAL(18,2)   DEFAULT 0.00,
    business_date   DATE            NOT NULL,
    days_past_due   INT             DEFAULT 0,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_loansched_account FOREIGN KEY (loan_account_id) REFERENCES loan_accounts(id)
);
CREATE INDEX idx_loansched_tenant_account ON loan_schedules (tenant_id, loan_account_id);
CREATE INDEX idx_loansched_due_date ON loan_schedules (tenant_id, due_date);
CREATE INDEX idx_loansched_status ON loan_schedules (tenant_id, loan_account_id, status);

-- 8. TRANSACTION BATCHES (Enterprise batch control — Finacle/Temenos pattern)
CREATE TABLE transaction_batches (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    branch_id       BIGINT,
    business_date   DATE            NOT NULL,
    batch_name      VARCHAR(50)     NOT NULL,
    batch_type      VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    opened_by       VARCHAR(100)    NOT NULL,
    opened_at       DATETIME2       NOT NULL,
    closed_by       VARCHAR(100),
    closed_at       DATETIME2,
    total_transactions INT          NOT NULL DEFAULT 0,
    total_debit     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total_credit    DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    maker_id        VARCHAR(100),
    checker_id      VARCHAR(100),
    approval_status VARCHAR(20),
    remarks         VARCHAR(500),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_txnbatch_tenant_date_name UNIQUE (tenant_id, business_date, batch_name),
    CONSTRAINT fk_txnbatch_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_txnbatch_tenant_date ON transaction_batches (tenant_id, business_date);
CREATE INDEX idx_txnbatch_tenant_date_status ON transaction_batches (tenant_id, business_date, status);
CREATE INDEX idx_txnbatch_branch ON transaction_batches (tenant_id, branch_id, business_date);

-- 9. LOAN TRANSACTIONS (no cascade delete — financial data)
CREATE TABLE loan_transactions (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    transaction_ref VARCHAR(40)     NOT NULL,
    loan_account_id BIGINT          NOT NULL,
    transaction_type VARCHAR(30)    NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL,
    principal_component DECIMAL(18,2) DEFAULT 0.00,
    interest_component DECIMAL(18,2) DEFAULT 0.00,
    penalty_component DECIMAL(18,2) DEFAULT 0.00,
    value_date      DATE            NOT NULL,
    posting_date    DATETIME2       NOT NULL,
    balance_after   DECIMAL(18,2)   NOT NULL,
    narration       VARCHAR(500),
    is_reversed     BIT             NOT NULL DEFAULT 0,
    reversed_by_ref VARCHAR(40),
    journal_entry_id BIGINT,
    idempotency_key VARCHAR(100),   -- CBS idempotency: client-supplied key to prevent duplicate processing
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_loantxn_ref UNIQUE (tenant_id, transaction_ref),
    CONSTRAINT fk_loantxn_account FOREIGN KEY (loan_account_id) REFERENCES loan_accounts(id)
);
CREATE INDEX idx_loantxn_tenant_account ON loan_transactions (tenant_id, loan_account_id);
CREATE INDEX idx_loantxn_txnref ON loan_transactions (tenant_id, transaction_ref);
CREATE INDEX idx_loantxn_value_date ON loan_transactions (tenant_id, value_date);
CREATE INDEX idx_loantxn_type ON loan_transactions (tenant_id, transaction_type);
-- CBS Idempotency: unique filtered index on non-null idempotency keys
-- Allows NULL (system txns) but enforces uniqueness on client-supplied keys
CREATE UNIQUE INDEX uq_loantxn_idempotency ON loan_transactions (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 10. GL MASTER
CREATE TABLE gl_master (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    gl_code         VARCHAR(20)     NOT NULL,
    gl_name         VARCHAR(200)    NOT NULL,
    account_type    VARCHAR(20)     NOT NULL,
    parent_gl_code  VARCHAR(20),
    debit_balance   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    credit_balance  DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    is_active       BIT             NOT NULL DEFAULT 1,
    is_header_account BIT           NOT NULL DEFAULT 0,
    description     VARCHAR(500),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_gl_tenant_code UNIQUE (tenant_id, gl_code)
);
CREATE INDEX idx_gl_tenant_code ON gl_master (tenant_id, gl_code);
CREATE INDEX idx_gl_tenant_active ON gl_master (tenant_id, is_active);

-- 11. JOURNAL ENTRIES
CREATE TABLE journal_entries (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    journal_ref     VARCHAR(40)     NOT NULL,
    value_date      DATE            NOT NULL,
    posting_date    DATETIME2       NOT NULL,
    narration       VARCHAR(500),
    total_debit     DECIMAL(18,2)   NOT NULL,
    total_credit    DECIMAL(18,2)   NOT NULL,
    source_module   VARCHAR(50),
    source_ref      VARCHAR(100),
    is_reversed     BIT             NOT NULL DEFAULT 0,
    is_posted       BIT             NOT NULL DEFAULT 0,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_journal_ref UNIQUE (tenant_id, journal_ref)
);
CREATE INDEX idx_journal_tenant_ref ON journal_entries (tenant_id, journal_ref);
CREATE INDEX idx_journal_value_date ON journal_entries (tenant_id, value_date);
CREATE INDEX idx_journal_posting_date ON journal_entries (tenant_id, posting_date);

-- 12. JOURNAL ENTRY LINES
CREATE TABLE journal_entry_lines (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    journal_entry_id BIGINT         NOT NULL,
    gl_code         VARCHAR(20)     NOT NULL,
    gl_name         VARCHAR(200),
    debit_credit    VARCHAR(10)     NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL,
    narration       VARCHAR(500),
    line_number     INT             NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_jeline_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id)
);
CREATE INDEX idx_jeline_journal ON journal_entry_lines (journal_entry_id);
CREATE INDEX idx_jeline_gl ON journal_entry_lines (tenant_id, gl_code);

-- 13. LEDGER ENTRIES (Append-only immutable financial ledger — RBI audit grade)
CREATE TABLE ledger_entries (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    ledger_sequence BIGINT          NOT NULL,
    journal_entry_id BIGINT         NOT NULL,
    journal_ref     VARCHAR(40)     NOT NULL,
    gl_code         VARCHAR(20)     NOT NULL,
    gl_name         VARCHAR(200),
    account_reference VARCHAR(40),
    business_date   DATE            NOT NULL,
    value_date      DATE            NOT NULL,
    debit_amount    DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    credit_amount   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    running_balance DECIMAL(18,2),
    module_code     VARCHAR(50),
    narration       VARCHAR(500),
    hash_value      VARCHAR(64)     NOT NULL,
    previous_hash   VARCHAR(64)     NOT NULL,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    created_by      VARCHAR(100)
);
-- No @Version column — ledger entries are immutable (no updates allowed)
-- No UPDATE or DELETE triggers should be allowed (same pattern as audit_logs)
-- UNIQUE constraint on (tenant_id, ledger_sequence) — DB-level safety net for hash chain integrity
CREATE UNIQUE INDEX uq_ledger_tenant_seq ON ledger_entries (tenant_id, ledger_sequence);
CREATE INDEX idx_ledger_tenant_gl ON ledger_entries (tenant_id, gl_code, business_date);
CREATE INDEX idx_ledger_tenant_date ON ledger_entries (tenant_id, business_date);
CREATE INDEX idx_ledger_journal ON ledger_entries (tenant_id, journal_entry_id);

-- 14. APPROVAL WORKFLOWS
CREATE TABLE approval_workflows (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    entity_type     VARCHAR(50)     NOT NULL,
    entity_id       BIGINT          NOT NULL,
    action_type     VARCHAR(50)     NOT NULL,
    status          VARCHAR(30)     NOT NULL DEFAULT 'PENDING_APPROVAL',
    maker_user_id   VARCHAR(100)    NOT NULL,
    checker_user_id VARCHAR(100),
    maker_remarks   VARCHAR(1000),
    checker_remarks VARCHAR(1000),
    submitted_at    DATETIME2       NOT NULL,
    actioned_at     DATETIME2,
    payload_snapshot NVARCHAR(MAX),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100)
);
CREATE INDEX idx_wf_entity ON approval_workflows (tenant_id, entity_type, entity_id);
CREATE INDEX idx_wf_status ON approval_workflows (tenant_id, status);
CREATE INDEX idx_wf_checker ON approval_workflows (tenant_id, checker_user_id);

-- 15. AUDIT LOGS (append-only, no updates, no deletes)
CREATE TABLE audit_logs (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    entity_type     VARCHAR(50)     NOT NULL,
    entity_id       BIGINT          NOT NULL,
    action          VARCHAR(50)     NOT NULL,
    before_snapshot NVARCHAR(MAX),
    after_snapshot  NVARCHAR(MAX),
    performed_by    VARCHAR(100)    NOT NULL,
    ip_address      VARCHAR(45),
    event_timestamp DATETIME2       NOT NULL,
    hash            VARCHAR(64)     NOT NULL,
    previous_hash   VARCHAR(64)     NOT NULL,
    module          VARCHAR(50),
    description     VARCHAR(1000)
);
-- No @Version column - audit logs are immutable
-- No UPDATE or DELETE triggers should be allowed
CREATE INDEX idx_audit_entity ON audit_logs (tenant_id, entity_type, entity_id);
CREATE INDEX idx_audit_timestamp ON audit_logs (tenant_id, event_timestamp);
CREATE INDEX idx_audit_user ON audit_logs (tenant_id, performed_by);

-- 16. BATCH JOBS
CREATE TABLE batch_jobs (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    job_name        VARCHAR(100)    NOT NULL,
    business_date   DATE            NOT NULL,
    status          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    started_at      DATETIME2,
    completed_at    DATETIME2,
    total_records   INT             DEFAULT 0,
    processed_records INT           DEFAULT 0,
    failed_records  INT             DEFAULT 0,
    error_message   NVARCHAR(MAX),
    retry_count     INT             NOT NULL DEFAULT 0,
    max_retries     INT             NOT NULL DEFAULT 3,
    step_name       VARCHAR(100),
    initiated_by    VARCHAR(100),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100)
);
CREATE INDEX idx_batch_tenant_date ON batch_jobs (tenant_id, business_date);
CREATE INDEX idx_batch_status ON batch_jobs (tenant_id, status);

-- 17. TRANSACTION LIMITS (CBS Internal Controls — per-role amount limits)
CREATE TABLE transaction_limits (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    role            VARCHAR(20)     NOT NULL,
    transaction_type VARCHAR(30)    NOT NULL,
    per_transaction_limit DECIMAL(18,2),
    daily_aggregate_limit DECIMAL(18,2),
    branch_id       BIGINT,
    is_active       BIT             NOT NULL DEFAULT 1,
    description     VARCHAR(500),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_txnlimit_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_txnlimit_tenant_role ON transaction_limits (tenant_id, role, transaction_type);

-- 18. APP USERS
CREATE TABLE app_users (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    username        VARCHAR(100)    NOT NULL,
    password_hash   VARCHAR(256)    NOT NULL,
    full_name       VARCHAR(200)    NOT NULL,
    email           VARCHAR(200),
    role            VARCHAR(20)     NOT NULL,
    is_active       BIT             NOT NULL DEFAULT 1,
    is_locked       BIT             NOT NULL DEFAULT 0,
    failed_login_attempts INT       NOT NULL DEFAULT 0,
    branch_id       BIGINT,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_user_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT fk_user_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_user_tenant_username ON app_users (tenant_id, username);

-- ============================================================
-- PROTECT AUDIT LOG FROM MODIFICATIONS
-- ============================================================
GO
CREATE TRIGGER trg_audit_no_update ON audit_logs
INSTEAD OF UPDATE
AS
BEGIN
    RAISERROR ('Audit log records cannot be updated - immutability enforced', 16, 1);
    ROLLBACK TRANSACTION;
END;
GO

CREATE TRIGGER trg_audit_no_delete ON audit_logs
INSTEAD OF DELETE
AS
BEGIN
    RAISERROR ('Audit log records cannot be deleted - immutability enforced', 16, 1);
    ROLLBACK TRANSACTION;
END;
GO

-- ============================================================
-- PROTECT LEDGER ENTRIES FROM MODIFICATIONS (RBI audit grade)
-- ============================================================
GO
CREATE TRIGGER trg_ledger_no_update ON ledger_entries
INSTEAD OF UPDATE
AS
BEGIN
    RAISERROR ('Ledger entries cannot be updated - immutability enforced per RBI audit requirements', 16, 1);
    ROLLBACK TRANSACTION;
END;
GO

CREATE TRIGGER trg_ledger_no_delete ON ledger_entries
INSTEAD OF DELETE
AS
BEGIN
    RAISERROR ('Ledger entries cannot be deleted - immutability enforced per RBI audit requirements', 16, 1);
    ROLLBACK TRANSACTION;
END;
GO
