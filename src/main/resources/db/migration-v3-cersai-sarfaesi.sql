-- ============================================================
-- Finvanta CBS — Migration V3d: CERSAI / SARFAESI Tables
-- Per SARFAESI Act 2002, CERSAI Registration Rules,
-- and RBI Master Circular on Wilful Defaulters.
-- Run AFTER migration-v2-tier1-hardening.sql.
-- ============================================================

-- 1. CERSAI Security Interest Registration
-- Per SARFAESI Act 2002 Section 26: All secured creditors must
-- register security interest with CERSAI within 30 days of creation.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='cersai_registrations')
CREATE TABLE cersai_registrations (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    registration_ref    VARCHAR(40)     NOT NULL,
    loan_account_id     BIGINT          NOT NULL,
    collateral_id       BIGINT          NOT NULL,
    cersai_id           VARCHAR(30),
    asset_category      VARCHAR(30)     NOT NULL,
    -- IMMOVABLE, MOVABLE, INTANGIBLE, RECEIVABLES
    asset_description   NVARCHAR(500)   NOT NULL,
    security_interest_type VARCHAR(30)  NOT NULL,
    -- MORTGAGE, HYPOTHECATION, PLEDGE, ASSIGNMENT, LIEN
    creation_date       DATE            NOT NULL,
    registration_date   DATE,
    modification_date   DATE,
    satisfaction_date   DATE,
    registration_deadline DATE          NOT NULL,
    -- creation_date + 30 days per SARFAESI Act
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, REGISTERED, MODIFIED, SATISFIED, FAILED
    cersai_response     NVARCHAR(1000),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_cersai_ref UNIQUE (tenant_id, registration_ref)
);
GO
CREATE INDEX idx_cersai_loan ON cersai_registrations (tenant_id, loan_account_id);
CREATE INDEX idx_cersai_status ON cersai_registrations (tenant_id, status);
CREATE INDEX idx_cersai_deadline ON cersai_registrations (tenant_id, registration_deadline)
    WHERE status = 'PENDING';
GO

-- 2. SARFAESI Notices — Section 13(2) demand notice tracking
-- Per SARFAESI Act 2002: 60-day demand notice before enforcement.
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='sarfaesi_notices')
CREATE TABLE sarfaesi_notices (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    notice_ref          VARCHAR(40)     NOT NULL,
    loan_account_id     BIGINT          NOT NULL,
    customer_id         BIGINT          NOT NULL,
    notice_type         VARCHAR(30)     NOT NULL,
    -- DEMAND_13_2, POSSESSION_13_4, SALE_NOTICE, ASSIGNMENT_NOTICE
    notice_date         DATE            NOT NULL,
    demand_amount       DECIMAL(18,2)   NOT NULL,
    response_deadline   DATE            NOT NULL,
    -- notice_date + 60 days for Section 13(2)
    customer_response   NVARCHAR(2000),
    response_date       DATE,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ISSUED',
    -- ISSUED, DELIVERED, RESPONSE_RECEIVED, EXPIRED, ENFORCED, WITHDRAWN
    delivery_mode       VARCHAR(30),
    -- REGISTERED_POST, SPEED_POST, HAND_DELIVERY, PUBLICATION
    delivery_date       DATE,
    delivery_proof      VARCHAR(200),
    enforcement_date    DATE,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_sarfaesi_ref UNIQUE (tenant_id, notice_ref)
);
GO
CREATE INDEX idx_sarfaesi_loan ON sarfaesi_notices (tenant_id, loan_account_id);
CREATE INDEX idx_sarfaesi_status ON sarfaesi_notices (tenant_id, status);
GO

-- 3. NPA Recovery tracking — OTS, DRT, SARFAESI enforcement
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='npa_recovery_actions')
CREATE TABLE npa_recovery_actions (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id           VARCHAR(20)     NOT NULL,
    action_ref          VARCHAR(40)     NOT NULL,
    loan_account_id     BIGINT          NOT NULL,
    action_type         VARCHAR(30)     NOT NULL,
    -- OTS, DRT_FILING, SARFAESI_13_2, SARFAESI_13_4, SARFAESI_SALE,
    -- LOK_ADALAT, CIVIL_SUIT, WRITE_OFF_TECHNICAL, WRITE_OFF_PRUDENTIAL
    action_date         DATE            NOT NULL,
    amount_demanded     DECIMAL(18,2),
    amount_recovered    DECIMAL(18,2)   DEFAULT 0.00,
    amount_settled      DECIMAL(18,2),
    status              VARCHAR(20)     NOT NULL DEFAULT 'INITIATED',
    -- INITIATED, IN_PROGRESS, SETTLED, RECOVERED, FAILED, WITHDRAWN
    remarks             NVARCHAR(2000),
    next_hearing_date   DATE,
    drt_case_number     VARCHAR(50),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME2       NOT NULL DEFAULT GETDATE(),
    updated_at          DATETIME2,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    CONSTRAINT uq_recovery_ref UNIQUE (tenant_id, action_ref)
);
GO
CREATE INDEX idx_recovery_loan ON npa_recovery_actions (tenant_id, loan_account_id);
CREATE INDEX idx_recovery_status ON npa_recovery_actions (tenant_id, status);
GO
