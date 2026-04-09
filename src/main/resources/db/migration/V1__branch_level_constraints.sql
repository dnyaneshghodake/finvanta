-- ============================================================================
-- CBS Tier-1 Branch-Level Isolation — Database Constraints
-- Per Finacle BRANCH_MASTER / RBI Banking Regulation Act 1949 Section 23
--
-- These constraints cannot be expressed via JPA annotations and must be
-- applied via DDL migration. For H2 (dev/test), some constraints use
-- H2-compatible syntax. For production (PostgreSQL/SQL Server), use the
-- vendor-specific partial index syntax.
-- ============================================================================

-- ============================================================================
-- 1. Single Head Office per Tenant
-- Per Finacle: exactly one branch with is_head_office=true per tenant.
-- HO is the consolidation point for inter-branch settlement and regulatory reporting.
--
-- H2 does not support partial/filtered unique indexes.
-- For H2 (dev/test): use a unique constraint on a computed column or trigger.
-- For PostgreSQL: CREATE UNIQUE INDEX uq_branch_tenant_ho ON branches (tenant_id) WHERE is_head_office = true;
-- For SQL Server: CREATE UNIQUE INDEX uq_branch_tenant_ho ON branches (tenant_id) WHERE is_head_office = 1;
--
-- H2-compatible approach: create a unique index on (tenant_id, is_head_office)
-- filtered via a generated column. Since H2 12+ supports generated columns:
-- ============================================================================

-- Note: H2 does not support filtered indexes. This constraint is enforced at
-- application level via Branch.@PrePersist/validateHierarchyInvariants() and
-- BranchRepository.findHeadOffice(tenantId) uniqueness check in service layer.
-- Production DDL (PostgreSQL):
-- CREATE UNIQUE INDEX IF NOT EXISTS uq_branch_tenant_ho
--     ON branches (tenant_id) WHERE is_head_office = true;

-- ============================================================================
-- 2. GLBranchBalance → GLMaster Foreign Key
-- Per Finacle GL_BRANCH: branch balance gl_code must reference a valid GLMaster.
-- Prevents orphaned branch balances if a GL code is deleted.
-- ============================================================================

-- Note: GLBranchBalance.gl_code references GLMaster.gl_code (logical key, not PK).
-- Standard FK requires referencing a UNIQUE column. GLMaster has a unique index on
-- (tenant_id, gl_code). For cross-table FK on composite key in JPA, this requires
-- a @ManyToOne relationship which we intentionally avoided for flexibility.
-- Application-level enforcement: AccountingService.updateGLBalances() always validates
-- GL existence via findAndLockByTenantIdAndGlCode() before creating GLBranchBalance.

-- Production DDL (PostgreSQL) — if strict FK is desired:
-- ALTER TABLE gl_branch_balances
--     ADD CONSTRAINT fk_glbb_gl_code
--     FOREIGN KEY (tenant_id, gl_code)
--     REFERENCES gl_master (tenant_id, gl_code)
--     ON DELETE RESTRICT;

-- ============================================================================
-- 3. Operational Branch Validation Index
-- Per Finacle: only BRANCH type can have customers, accounts, and transactions.
-- This index supports efficient lookup of operational branches for EOD processing.
-- ============================================================================

-- Already defined in JPA @Table indexes, but documenting here for completeness:
-- idx_branch_tenant_type ON branches (tenant_id, branch_type)
-- idx_branch_tenant_parent ON branches (tenant_id, parent_branch_id)

-- ============================================================================
-- 4. Business Calendar Branch-Date Uniqueness
-- Per Finacle DAYCTRL: one calendar entry per branch per date.
-- Already defined in JPA @Table unique index:
-- idx_buscal_tenant_branch_date ON business_calendar (tenant_id, branch_id, business_date) UNIQUE
-- ============================================================================

-- ============================================================================
-- 5. GLBranchBalance Branch-GL Uniqueness
-- Per Finacle GL_BRANCH: one balance row per branch per GL code.
-- Already defined in JPA @Table unique index:
-- idx_glbb_tenant_branch_gl ON gl_branch_balances (tenant_id, branch_id, gl_code) UNIQUE
-- ============================================================================
