-- ============================================================
-- Finvanta CBS — Migration V3a: DB-Level Immutability Triggers
-- Per RBI IT Governance Direction 2023 Section 8.3.
-- Run AFTER migration-v2-tier1-hardening.sql.
--
-- Ledger entries and audit logs must be tamper-proof at the DB level.
-- Application-level immutability (no setter calls) is necessary but
-- insufficient — a DBA with direct DB access could UPDATE/DELETE.
-- These triggers provide defense-in-depth per Finacle LEDGER_GUARD.
-- ============================================================

-- 1. Prevent UPDATE on ledger_entries
IF OBJECT_ID('trg_ledger_entries_no_update', 'TR') IS NOT NULL
    DROP TRIGGER trg_ledger_entries_no_update;
GO
CREATE TRIGGER trg_ledger_entries_no_update
ON ledger_entries
INSTEAD OF UPDATE
AS
BEGIN
    RAISERROR(
        'CBS IMMUTABILITY: ledger_entries cannot be updated. Use reversal journal entry.',
        16, 1);
    ROLLBACK TRANSACTION;
END;
GO

-- 2. Prevent DELETE on ledger_entries
IF OBJECT_ID('trg_ledger_entries_no_delete', 'TR') IS NOT NULL
    DROP TRIGGER trg_ledger_entries_no_delete;
GO
CREATE TRIGGER trg_ledger_entries_no_delete
ON ledger_entries
INSTEAD OF DELETE
AS
BEGIN
    RAISERROR(
        'CBS IMMUTABILITY: ledger_entries cannot be deleted. 8-year retention per RBI.',
        16, 1);
    ROLLBACK TRANSACTION;
END;
GO

-- 3. Prevent UPDATE on audit_logs
IF OBJECT_ID('trg_audit_logs_no_update', 'TR') IS NOT NULL
    DROP TRIGGER trg_audit_logs_no_update;
GO
CREATE TRIGGER trg_audit_logs_no_update
ON audit_logs
INSTEAD OF UPDATE
AS
BEGIN
    RAISERROR(
        'CBS IMMUTABILITY: audit_logs cannot be updated. Per RBI IT Governance 2023.',
        16, 1);
    ROLLBACK TRANSACTION;
END;
GO

-- 4. Prevent DELETE on audit_logs
IF OBJECT_ID('trg_audit_logs_no_delete', 'TR') IS NOT NULL
    DROP TRIGGER trg_audit_logs_no_delete;
GO
CREATE TRIGGER trg_audit_logs_no_delete
ON audit_logs
INSTEAD OF DELETE
AS
BEGIN
    RAISERROR(
        'CBS IMMUTABILITY: audit_logs cannot be deleted. 8-year retention per RBI.',
        16, 1);
    ROLLBACK TRANSACTION;
END;
GO

-- 5. Prevent modification of financial amounts on posted journal entries
-- Draft entries (is_posted = 0) can still be updated before posting.
-- Posted entries allow only is_reversed flag change (reversal workflow).
IF OBJECT_ID('trg_journal_entries_no_update_posted', 'TR') IS NOT NULL
    DROP TRIGGER trg_journal_entries_no_update_posted;
GO
CREATE TRIGGER trg_journal_entries_no_update_posted
ON journal_entries
AFTER UPDATE
AS
BEGIN
    IF EXISTS (
        SELECT 1 FROM inserted i
        INNER JOIN deleted d ON i.id = d.id
        WHERE d.is_posted = 1
        AND (i.total_debit != d.total_debit
             OR i.total_credit != d.total_credit
             OR i.value_date != d.value_date
             OR i.journal_ref != d.journal_ref)
    )
    BEGIN
        RAISERROR(
            'CBS IMMUTABILITY: Posted journal financial fields cannot be modified.',
            16, 1);
        ROLLBACK TRANSACTION;
    END;
END;
GO
