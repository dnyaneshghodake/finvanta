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
    -- CBS Customer Exposure Limits (Finacle CIF_LIMIT / RBI Exposure Norms)
    monthly_income  DECIMAL(18,2),
    max_borrowing_limit DECIMAL(18,2),
    employment_type VARCHAR(30),
    employer_name   VARCHAR(200),
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

-- 7. LOAN ACCOUNTS
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
    -- Multi-Disbursement (Finacle DISB_MASTER / Temenos AA.DISBURSEMENT)
    disbursement_mode VARCHAR(20)    DEFAULT 'SINGLE',       -- SINGLE, MULTI_TRANCHE, DRAWDOWN
    total_tranches_planned INT,
    tranches_disbursed INT           DEFAULT 0,
    is_fully_disbursed BIT           NOT NULL DEFAULT 0,
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

-- 8. LOAN SCHEDULES (Amortization — generated at disbursement)
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

-- 9. TRANSACTION BATCHES (Enterprise batch control — Finacle/Temenos pattern)
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

-- 10. LOAN TRANSACTIONS (no cascade delete — financial data)
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
    voucher_number  VARCHAR(40),    -- CBS voucher: VCH/branch/YYYYMMDD/seq — for reconciliation queries
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
-- CBS Voucher Reconciliation: index for branch-level daily voucher register queries
CREATE INDEX idx_loantxn_voucher ON loan_transactions (tenant_id, voucher_number);
-- CBS Idempotency: unique filtered index on non-null idempotency keys
-- Allows NULL (system txns) but enforces uniqueness on client-supplied keys
CREATE UNIQUE INDEX uq_loantxn_idempotency ON loan_transactions (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 11. GL MASTER
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

-- 12. JOURNAL ENTRIES
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

-- 13. JOURNAL ENTRY LINES
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

-- 14. LEDGER ENTRIES (Append-only immutable financial ledger — RBI audit grade)
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

-- 15. APPROVAL WORKFLOWS
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

-- 16. AUDIT LOGS (append-only, no updates, no deletes)
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

-- 17. BATCH JOBS
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

-- 18. TRANSACTION LIMITS (CBS Internal Controls — per-role amount limits)
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

-- 19. DB SEQUENCES (Finacle SEQ_MASTER / Temenos EB.SEQUENCE — portable sequence generator)
CREATE TABLE db_sequences (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    sequence_name   VARCHAR(100)    NOT NULL,
    current_value   BIGINT          NOT NULL DEFAULT 0,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_dbseq_tenant_name UNIQUE (tenant_id, sequence_name)
);
CREATE INDEX idx_dbseq_tenant_name ON db_sequences (tenant_id, sequence_name);

-- 20. DISBURSEMENT SCHEDULES (Finacle DISB_MASTER / Temenos AA.DISBURSEMENT.ARRANGEMENT)
CREATE TABLE disbursement_schedules (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    loan_account_id BIGINT          NOT NULL,
    tranche_number  INT             NOT NULL,
    tranche_amount  DECIMAL(18,2)   NOT NULL,
    tranche_percentage DECIMAL(8,2),
    milestone_description VARCHAR(500) NOT NULL,
    expected_date   DATE,
    actual_date     DATE,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PLANNED',
    condition_verified_by VARCHAR(100),
    condition_verified_date DATE,
    approved_by     VARCHAR(100),
    approved_date   DATE,
    transaction_ref VARCHAR(40),
    voucher_number  VARCHAR(40),
    remarks         VARCHAR(500),
    beneficiary_name VARCHAR(200),
    beneficiary_account VARCHAR(40),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_disbsched_account FOREIGN KEY (loan_account_id) REFERENCES loan_accounts(id)
);
CREATE INDEX idx_disbsched_account ON disbursement_schedules (tenant_id, loan_account_id);
CREATE INDEX idx_disbsched_status ON disbursement_schedules (tenant_id, status);

-- 21. COLLATERALS (Finacle COLMAS / Temenos AA.COLLATERAL)
CREATE TABLE collaterals (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    collateral_ref  VARCHAR(40)     NOT NULL,
    loan_application_id BIGINT      NOT NULL,
    customer_id     BIGINT          NOT NULL,
    collateral_type VARCHAR(30)     NOT NULL,
    description     VARCHAR(500),
    owner_name      VARCHAR(200)    NOT NULL,
    owner_relationship VARCHAR(30)  NOT NULL,
    -- Gold-specific
    gold_purity     VARCHAR(10),
    gold_weight_grams DECIMAL(10,3),
    gold_net_weight_grams DECIMAL(10,3),
    gold_rate_per_gram DECIMAL(10,2),
    -- Property-specific
    property_address VARCHAR(500),
    property_type   VARCHAR(30),
    property_area_sqft DECIMAL(12,2),
    registration_number VARCHAR(100),
    registration_date DATE,
    -- Vehicle-specific
    vehicle_type    VARCHAR(30),
    vehicle_registration VARCHAR(20),
    vehicle_make    VARCHAR(50),
    vehicle_model   VARCHAR(50),
    vehicle_year    INT,
    -- FD-specific
    fd_number       VARCHAR(50),
    fd_bank_name    VARCHAR(100),
    fd_amount       DECIMAL(18,2),
    fd_maturity_date DATE,
    -- Valuation
    market_value    DECIMAL(18,2),
    forced_sale_value DECIMAL(18,2),
    valuation_date  DATE,
    valuation_amount DECIMAL(18,2),
    valuator_name   VARCHAR(200),
    valuator_firm   VARCHAR(200),
    valuator_license VARCHAR(50),
    valuation_validity_months INT,
    -- Lien
    lien_status     VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    lien_creation_date DATE,
    lien_reference  VARCHAR(100),
    -- Insurance
    insurance_policy_number VARCHAR(50),
    insurance_company VARCHAR(200),
    insurance_expiry_date DATE,
    insurance_amount DECIMAL(18,2),
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_collateral_ref UNIQUE (tenant_id, collateral_ref),
    CONSTRAINT fk_collateral_app FOREIGN KEY (loan_application_id) REFERENCES loan_applications(id),
    CONSTRAINT fk_collateral_cust FOREIGN KEY (customer_id) REFERENCES customers(id)
);
CREATE INDEX idx_collateral_tenant_ref ON collaterals (tenant_id, collateral_ref);
CREATE INDEX idx_collateral_loan ON collaterals (tenant_id, loan_application_id);
CREATE INDEX idx_collateral_type ON collaterals (tenant_id, collateral_type);

-- 21. LOAN DOCUMENTS (Finacle DOCMAS / Temenos AA.DOCUMENT)
CREATE TABLE loan_documents (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    loan_application_id BIGINT      NOT NULL,
    document_type   VARCHAR(50)     NOT NULL,
    document_name   VARCHAR(200)    NOT NULL,
    file_name       VARCHAR(255)    NOT NULL,
    file_path       VARCHAR(500)    NOT NULL,
    file_size       BIGINT,
    content_type    VARCHAR(100),
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_by     VARCHAR(100),
    verified_date   DATE,
    rejection_reason VARCHAR(500),
    expiry_date     DATE,
    is_mandatory    BIT             NOT NULL DEFAULT 0,
    remarks         VARCHAR(500),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_loandoc_app FOREIGN KEY (loan_application_id) REFERENCES loan_applications(id)
);
CREATE INDEX idx_loandoc_app ON loan_documents (tenant_id, loan_application_id);
CREATE INDEX idx_loandoc_type ON loan_documents (tenant_id, document_type);

-- 22. APP USERS
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

-- ============================================================
-- P0-1: CHARGE CONFIGURATION (Finacle CHRG_MASTER)
-- ============================================================
CREATE TABLE charge_config (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    charge_code     VARCHAR(50)     NOT NULL,
    charge_name     VARCHAR(200),
    event_trigger   VARCHAR(50)     NOT NULL, -- DISBURSEMENT, OVERDUE_EMI, CHEQUE_RETURN, ACCOUNT_CLOSURE, MANUAL
    calculation_type VARCHAR(20)    NOT NULL, -- FLAT, PERCENTAGE, SLAB
    base_amount     DECIMAL(18,2),
    percentage      DECIMAL(5,2),
    slab_json       NVARCHAR(MAX),
    min_amount      DECIMAL(18,2),
    max_amount      DECIMAL(18,2),
    gst_applicable  BIT             NOT NULL DEFAULT 1,
    gst_rate        DECIMAL(5,2)    DEFAULT 18.00,
    gl_charge_income VARCHAR(10),
    gl_gst_payable  VARCHAR(10)     DEFAULT '2200',
    waiver_allowed  BIT             NOT NULL DEFAULT 1,
    max_waiver_percent DECIMAL(5,2),
    product_code    VARCHAR(50),    -- NULL means applies to all products
    is_active       BIT             NOT NULL DEFAULT 1,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_chargeconfig_tenant_code_product UNIQUE (tenant_id, charge_code, product_code)
);
CREATE INDEX idx_chargeconfig_tenant_code ON charge_config (tenant_id, charge_code);
CREATE INDEX idx_chargeconfig_tenant_product ON charge_config (tenant_id, product_code);
CREATE INDEX idx_chargeconfig_tenant_active ON charge_config (tenant_id, is_active);
GO

-- ============================================================
-- P0-2: INTEREST ACCRUAL (Audit-grade per-day records)
-- ============================================================
CREATE TABLE interest_accruals (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    account_id      BIGINT          NOT NULL,
    accrual_date    DATE            NOT NULL,
    principal_base  DECIMAL(18,2)   NOT NULL,
    rate_applied    DECIMAL(8,4)    NOT NULL,
    days_count      INT             NOT NULL,
    accrued_amount  DECIMAL(18,2)   NOT NULL,
    accrual_type    VARCHAR(20)     NOT NULL, -- REGULAR, PENAL
    posted_flag     BIT             NOT NULL DEFAULT 0,
    posting_date    DATE,
    journal_entry_id BIGINT,
    transaction_ref VARCHAR(50),
    business_date   DATE            NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_intaccrual_account FOREIGN KEY (account_id) REFERENCES loan_accounts(id)
);
CREATE INDEX idx_intaccrual_tenant_account_date ON interest_accruals (tenant_id, account_id, accrual_date);
CREATE INDEX idx_intaccrual_tenant_account_type ON interest_accruals (tenant_id, account_id, accrual_type);
CREATE INDEX idx_intaccrual_posted_flag ON interest_accruals (posted_flag);
CREATE INDEX idx_intaccrual_business_date ON interest_accruals (business_date);
GO

-- ============================================================
-- P1-1: INTER-BRANCH TRANSACTIONS (Finacle IB_SETTLEMENT)
-- ============================================================
CREATE TABLE inter_branch_transactions (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    source_branch_id BIGINT         NOT NULL,
    target_branch_id BIGINT         NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL,
    source_journal_id BIGINT,
    target_journal_id BIGINT,
    settlement_status VARCHAR(20)   NOT NULL, -- PENDING, SETTLED, FAILED
    settlement_batch_ref VARCHAR(50),
    business_date   DATE            NOT NULL,
    failure_reason  VARCHAR(500),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT fk_ibxfr_source_branch FOREIGN KEY (source_branch_id) REFERENCES branches(id),
    CONSTRAINT fk_ibxfr_target_branch FOREIGN KEY (target_branch_id) REFERENCES branches(id)
);
CREATE INDEX idx_ibxfr_tenant_sourcetarget ON inter_branch_transactions (tenant_id, source_branch_id, target_branch_id);
CREATE INDEX idx_ibxfr_settlement_status ON inter_branch_transactions (settlement_status);
CREATE INDEX idx_ibxfr_business_date ON inter_branch_transactions (business_date);
GO

-- ============================================================
-- P1-2: CLEARING TRANSACTIONS (Finacle CLG_MASTER)
-- ============================================================
CREATE TABLE clearing_transactions (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(20)     NOT NULL,
    clearing_ref    VARCHAR(50)     NOT NULL,
    source_type     VARCHAR(20)     NOT NULL, -- NEFT, RTGS, IMPS, CHEQUE, UPI
    amount          DECIMAL(18,2)   NOT NULL,
    customer_account_ref VARCHAR(50) NOT NULL,
    counterparty_details VARCHAR(500),
    status          VARCHAR(20)     NOT NULL, -- INITIATED, PENDING, CONFIRMED, SETTLED, FAILED, REVERSED
    initiated_date  DATETIME2       NOT NULL DEFAULT GETDATE(),
    settlement_date DATETIME2,
    suspense_journal_id BIGINT,
    settlement_journal_id BIGINT,
    failure_reason  VARCHAR(500),
    business_date   DATE            NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    CONSTRAINT uq_clearing_ref UNIQUE (tenant_id, clearing_ref)
);
CREATE INDEX idx_clrg_tenant_ref ON clearing_transactions (tenant_id, clearing_ref);
CREATE INDEX idx_clrg_status ON clearing_transactions (status);
CREATE INDEX idx_clrg_initiated_date ON clearing_transactions (initiated_date);
CREATE INDEX idx_clrg_settlement_date ON clearing_transactions (settlement_date);
GO

-- ============================================================
-- P2-1: LEDGER PARTITIONING STRATEGY FOR PRODUCTION
-- ============================================================
-- For H2 (dev/test): Partitioning not available — this comment documents the strategy.
--
-- SQL Server Production Deployment DDL (commented out — uncomment when deploying):
--
-- Partition Function by Business Date (monthly partitions):
-- CREATE PARTITION FUNCTION pf_date_monthly (DATE)
--     AS RANGE LEFT FOR VALUES (
--         '2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30', '2026-05-31', '2026-06-30',
--         '2026-07-31', '2026-08-31', '2026-09-30', '2026-10-31', '2026-11-30', '2026-12-31'
--     );
--
-- Partition Scheme by Date:
-- CREATE PARTITION SCHEME ps_date_monthly
--     AS PARTITION pf_date_monthly
--     ALL TO ([PRIMARY]);
--
-- Partition ledger_entries by business_date:
-- ALTER TABLE ledger_entries
--     ADD CONSTRAINT pk_ledger_partitioned PRIMARY KEY (id, business_date)
--     ON ps_date_monthly (business_date);
--
-- Partition journal_entries by value_date:
-- ALTER TABLE journal_entries
--     ADD CONSTRAINT pk_journal_partitioned PRIMARY KEY (id, value_date)
--     ON ps_date_monthly (value_date);
--
-- Partition loan_transactions by value_date:
-- ALTER TABLE loan_transactions
--     ADD CONSTRAINT pk_loantxn_partitioned PRIMARY KEY (id, value_date)
--     ON ps_date_monthly (value_date);
--
-- Partitioning Benefits (10M+ TPS):
-- - Query filter on (business_date BETWEEN '2026-04-01' AND '2026-04-30') automatically prunes other partitions
-- - Maintenance windows (SHRINK, REBUILD INDEX) per partition (weekly/monthly)
-- - Archive old partitions (2+ year-old data) to separate filegroup for cost optimization
-- - Parallel table scans within a partition (SQL Server parallelism)
--
GO

-- ============================================================
-- PARTITION HINTS FOR HEAVY QUERIES (Heavy-load optimization)
-- ============================================================
-- LedgerEntryRepository queries will include @QueryHint(name = "org.hibernate.query.HINT_FETCHGRAPH")
-- for partition pruning on ledger_entries and journal_entries queries.
--
-- Example Hibernate query hint for partition-aware query:
-- @QueryHint(name = "org.hibernate.query.HINT_SQL_COMMENT", value = "/* Partition pruning: business_date filter */")
--
GO

