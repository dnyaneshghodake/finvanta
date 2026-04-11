-- ============================================================
-- Finvanta CBS - Seed Data (auto-loaded by Spring Boot)
-- Compatible with both H2 (dev) and SQL Server (sqlserver/prod).
-- Uses 1/0 instead of true/false for boolean columns (SQL Server bit type).
-- ============================================================

-- Tenant (created_at required — data.sql bypasses Hibernate @CreationTimestamp)
-- Per Finacle BANK_MASTER / Temenos COMPANY: tenant carries RBI regulatory identity.
-- tenant_code 'DEFAULT' is the partition key used across all tables (maps to Finacle BANK_ID).
-- RBI fields populated per Banking Regulation Act 1949 and RBI IT Governance Direction 2023.
INSERT INTO tenants (tenant_code, tenant_name, license_type, is_active, db_schema,
    rbi_bank_code, ifsc_prefix, license_number, regulatory_category,
    country_code, base_currency, timezone,
    created_at, created_by)
VALUES ('DEFAULT', 'Finvanta Demo Bank', 'ENTERPRISE', 1, 'dbo',
    '9999', 'FNVT', 'RBI/SCB/2026/DEMO-001', 'SCB',
    'IN', 'INR', 'Asia/Kolkata',
    CURRENT_TIMESTAMP, 'SYSTEM');

-- Branches (with Tier-1 branch hierarchy per Finacle SOL architecture)
-- HQ001 = Head Office (branchType=HEAD_OFFICE, is_head_office=true, no parent)
-- DEL001, BLR001 = Operational branches (branchType=BRANCH, parent=HQ001)
INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, branch_type, is_head_office, zone_code, region_code, version, created_at, created_by)
VALUES ('DEFAULT', 'HQ001', 'Head Office', 'FNVT0000001', 'Bandra Kurla Complex', 'Mumbai', 'Maharashtra', '400051', 1, 'WEST', 'HEAD_OFFICE', 1, 'WEST', 'MH', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, branch_type, is_head_office, parent_branch_id, zone_code, region_code, version, created_at, created_by)
VALUES ('DEFAULT', 'DEL001', 'New Delhi Main', 'FNVT0000002', 'Connaught Place', 'New Delhi', 'Delhi', '110001', 1, 'NORTH', 'BRANCH', 0, 1, 'NORTH', 'DL', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, branch_type, is_head_office, parent_branch_id, zone_code, region_code, version, created_at, created_by)
VALUES ('DEFAULT', 'BLR001', 'Bangalore Main', 'FNVT0000003', 'MG Road', 'Bangalore', 'Karnataka', '560001', 1, 'SOUTH', 'BRANCH', 0, 1, 'SOUTH', 'KA', 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Business Calendar (April 2026) — BRANCH-SCOPED per Tier-1 CBS
-- Per Finacle DAYCTRL: each branch has its own calendar entry per date.
-- Unique constraint: (tenant_id, branch_id, business_date)
-- Seeded for HQ001 (branch_id=1). Use Calendar > Generate for DEL001/BLR001.
-- Holiday descriptions inline per RBI NI Act (no separate UPDATE needed).
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-01', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-02', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-03', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-04', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-05', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-06', 1, 'Ram Navami', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-07', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-08', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-09', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-10', 1, 'Mahavir Jayanti', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-11', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-12', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-13', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-14', 1, 'Dr Ambedkar Jayanti', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-15', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-16', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-17', 1, 'Good Friday', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-18', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-19', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-20', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-21', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-22', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-23', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-24', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-25', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-26', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-27', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-28', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-29', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 1, 'HQ001', '2026-04-30', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- === DEL001 (branch_id=2) — April 1-15 working days ===
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-01', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-02', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-03', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-04', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-05', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-06', 1, 'Ram Navami', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-07', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-08', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-09', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-10', 1, 'Mahavir Jayanti', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-11', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-12', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-13', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-14', 1, 'Dr Ambedkar Jayanti', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 2, 'DEL001', '2026-04-15', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- === BLR001 (branch_id=3) — April 1-15 working days ===
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-01', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-02', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-03', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-04', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-05', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-06', 1, 'Ram Navami', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-07', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-08', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-09', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-10', 1, 'Mahavir Jayanti', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-11', 1, 'Saturday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-12', 1, 'Sunday', 'WEEKEND', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-13', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, holiday_description, holiday_type, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-14', 1, 'Dr Ambedkar Jayanti', 'NATIONAL', 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, branch_id, branch_code, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', 3, 'BLR001', '2026-04-15', 0, 'NOT_OPENED', 0, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- NOTE: Use Calendar > Generate for remaining dates (Apr 16-30 and beyond).

-- Customers (with CBS Exposure Limits per RBI Exposure Norms)
-- monthly_income: for DTI ratio check (total EMI <= 60% of income)
-- max_borrowing_limit: per-customer cap on total outstanding exposure
-- Sprint 1.2: Added is_pep, rekyc_due (NOT NULL), kyc_risk_category for new Customer entity fields
INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, kyc_verified_date, kyc_verified_by, cibil_score, customer_type, is_active, is_pep, rekyc_due, kyc_risk_category, branch_id, monthly_income, max_borrowing_limit, employment_type, employer_name, version, created_at, created_by)
VALUES ('DEFAULT', 'CUST001', 'Rajesh', 'Sharma', '1985-03-15', 'ABCDE1234F', '123456789012', '9876543210', 'rajesh.sharma@email.com', '123 MG Road', 'Mumbai', 'Maharashtra', '400001', 1, '2026-01-15', 'admin', 750, 'INDIVIDUAL', 1, 0, 0, 'LOW', 1, 150000.00, 5000000.00, 'SALARIED', 'Tata Consultancy Services', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, kyc_verified_date, kyc_verified_by, cibil_score, customer_type, is_active, is_pep, rekyc_due, kyc_risk_category, branch_id, monthly_income, max_borrowing_limit, employment_type, employer_name, version, created_at, created_by)
VALUES ('DEFAULT', 'CUST002', 'Priya', 'Patel', '1990-07-22', 'FGHIJ5678K', '234567890123', '9876543211', 'priya.patel@email.com', '456 Ring Road', 'Delhi', 'Delhi', '110002', 1, '2026-02-10', 'admin', 820, 'INDIVIDUAL', 1, 0, 0, 'MEDIUM', 2, 250000.00, 10000000.00, 'SALARIED', 'Infosys Limited', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, cibil_score, customer_type, is_active, is_pep, rekyc_due, kyc_risk_category, branch_id, monthly_income, max_borrowing_limit, employment_type, employer_name, version, created_at, created_by)
VALUES ('DEFAULT', 'CUST003', 'Arun', 'Kumar', '1978-11-05', 'KLMNO9012P', '345678901234', '9876543212', 'arun.kumar@email.com', '789 Brigade Road', 'Bangalore', 'Karnataka', '560002', 0, 580, 'INDIVIDUAL', 1, 0, 0, 'MEDIUM', 3, 80000.00, 2000000.00, 'SELF_EMPLOYED', NULL, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Product Master (Finacle PDDEF / Temenos AA.PRODUCT.CATALOG)
-- Per CBS standards: GL codes are configured per product, not hardcoded.
-- Sprint 1.4: Added interest_tiering_enabled (NOT NULL) for CASA tiering support
INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'TERM_LOAN', 'Term Loan - Unsecured', 'TERM_LOAN', 'Standard unsecured term loan for salaried individuals', 'INR', 'ACTUAL_365', 'FIXED', 8.0000, 24.0000, 2.0000, 50000.00, 5000000.00, 6, 84, 'MONTHLY', '1001', '1002', '1100', '4001', '4002', '4003', '5001', '1003', '5002', '2100', 1, 'INTEREST_FIRST', 0, 1.0000, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'HOME_LOAN', 'Home Loan - Secured', 'TERM_LOAN', 'Housing finance for residential property purchase', 'INR', 'ACTUAL_365', 'FLOATING', 6.5000, 12.0000, 2.0000, 500000.00, 50000000.00, 12, 360, 'MONTHLY', '1001', '1002', '1100', '4001', '4002', '4003', '5001', '1003', '5002', '2100', 1, 'INTEREST_FIRST', 0, 0.5000, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'GOLD_LOAN', 'Gold Loan - Secured', 'DEMAND_LOAN', 'Loan against gold ornaments with bullet repayment', 'INR', 'ACTUAL_365', 'FIXED', 7.0000, 15.0000, 2.0000, 10000.00, 2500000.00, 3, 12, 'BULLET', '1001', '1002', '1100', '4001', '4002', '4003', '5001', '1003', '5002', '2100', 1, 'INTEREST_FIRST', 1, 0.2500, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- GL Master (Chart of Accounts)
-- CBS IMPORTANT: All GL balances start at ZERO. Opening balances must be established
-- via a proper journal entry (DR Bank Ops / CR Share Capital) after the first Day Open.
-- This ensures GL reconciliation passes from day one — every GL balance is traceable
-- to a journal entry per double-entry accounting principles.
-- Per Finacle/Temenos Day Zero: the first transaction after system go-live is the
-- capital injection journal entry posted by ADMIN through the TransactionEngine.
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1000', 'Assets', 'ASSET', 0.00, 0.00, 1, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1001', 'Loan Portfolio - Term Loans', 'ASSET', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1002', 'Interest Receivable', 'ASSET', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1003', 'Provision for NPA', 'ASSET', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1100', 'Bank Account - Operations', 'ASSET', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2000', 'Liabilities', 'LIABILITY', 0.00, 0.00, 1, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2001', 'Customer Deposits', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2100', 'Interest Suspense - NPA', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2101', 'Sundry Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '3000', 'Equity', 'EQUITY', 0.00, 0.00, 1, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '3001', 'Share Capital', 'EQUITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4000', 'Income', 'INCOME', 0.00, 0.00, 1, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4001', 'Interest Income - Loans', 'INCOME', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4002', 'Fee Income', 'INCOME', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4003', 'Penal Interest Income', 'INCOME', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5000', 'Expenses', 'EXPENSE', 0.00, 0.00, 1, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5001', 'Provision Expense', 'EXPENSE', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5002', 'Write-Off Expense', 'EXPENSE', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- P0-1: NEW GL CODES FOR CHARGES AND GST
-- Inter-Branch GL codes (P1-1)
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1300', 'Inter-Branch Receivable', 'ASSET', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2200', 'CGST Payable', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2201', 'SGST Payable', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2300', 'Inter-Branch Payable', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Clearing Suspense GL (legacy — deprecated, use rail-specific GLs below)
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2400', 'Clearing Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Rail-Specific Clearing Suspense GLs per RBI Payment Systems Act 2007
-- Per RBI: each payment rail MUST have separate inward + outward suspense GLs
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2600', 'NEFT Outward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2601', 'NEFT Inward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2610', 'RTGS Outward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2611', 'RTGS Inward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2620', 'IMPS Outward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2621', 'IMPS Inward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2630', 'UPI Outward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2631', 'UPI Inward Suspense', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- RBI Settlement (Nostro) — bank's account with RBI for NEFT/RTGS settlement
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1400', 'RBI Settlement Nostro', 'ASSET', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- CASA Module GL Codes (Savings/Current Accounts per Finacle CUSTACCT)
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2010', 'Customer Deposits - Savings', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2020', 'Customer Deposits - Current', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2500', 'TDS Payable - Section 194A', 'LIABILITY', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4010', 'Interest Income - Deposits', 'INCOME', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5010', 'Interest Expense - Deposits', 'EXPENSE', 0.00, 0.00, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- CASA Deposit Products (Finacle PDDEF for CASA module)
-- Per Tier-1 CBS: interest rates and minimum balances are product-driven, not hardcoded.
-- product_category = 'CASA_SAVINGS' or 'CASA_CURRENT' distinguishes deposit products from loan products.
-- min_interest_rate/max_interest_rate: used as the default savings rate band.
-- min_loan_amount: repurposed as minimum_balance for CASA products.
-- max_loan_amount: repurposed as maximum_balance (0 = unlimited).
INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'SAVINGS', 'Savings Account - Regular', 'CASA_SAVINGS', 'Individual savings account per RBI norms. Interest: 4% p.a. daily product, credited quarterly.', 'INR', 'ACTUAL_365', 'FIXED', 4.0000, 4.0000, 0.0000, 5000.00, 0.00, 0, 0, 'QUARTERLY', '2010', '2010', '1100', '4010', '4002', '4003', '5010', '2010', '5002', '2100', 1, 'INTEREST_FIRST', 0, 0.0000, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'SAVINGS_PMJDY', 'Savings - PMJDY (Zero Balance)', 'CASA_SAVINGS', 'Pradhan Mantri Jan Dhan Yojana zero-balance savings account. Interest: 4% p.a.', 'INR', 'ACTUAL_365', 'FIXED', 4.0000, 4.0000, 0.0000, 0.00, 0.00, 0, 0, 'QUARTERLY', '2010', '2010', '1100', '4010', '4002', '4003', '5010', '2010', '5002', '2100', 1, 'INTEREST_FIRST', 0, 0.0000, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'SAVINGS_NRI', 'Savings - NRE/NRO', 'CASA_SAVINGS', 'NRI savings account per FEMA guidelines. Interest: 3.5% p.a.', 'INR', 'ACTUAL_365', 'FIXED', 3.5000, 3.5000, 0.0000, 10000.00, 0.00, 0, 0, 'QUARTERLY', '2010', '2010', '1100', '4010', '4002', '4003', '5010', '2010', '5002', '2100', 1, 'INTEREST_FIRST', 0, 0.0000, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, interest_tiering_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'CURRENT', 'Current Account - Business', 'CASA_CURRENT', 'Business current account. Zero interest per RBI norms. Min balance INR 10,000.', 'INR', 'ACTUAL_365', 'FIXED', 0.0000, 0.0000, 0.0000, 10000.00, 0.00, 0, 0, 'MONTHLY', '2020', '2020', '1100', '4010', '4002', '4003', '5010', '2020', '5002', '2100', 1, 'INTEREST_FIRST', 0, 0.0000, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- P0-1: CHARGE CONFIGURATIONS (Finacle CHRG_MASTER)
-- PROCESSING_FEE: 1% of loan amount, GST applicable (18%)
INSERT INTO charge_config (tenant_id, charge_code, charge_name, event_trigger, calculation_type, percentage, gst_applicable, gst_rate, gl_charge_income, gl_gst_payable, waiver_allowed, max_waiver_percent, product_code, is_active, version, created_at, created_by)
VALUES ('DEFAULT', 'PROCESSING_FEE', 'Processing Fee', 'DISBURSEMENT', 'PERCENTAGE', 1.00, 1, 18.00, '4002', '2200', 1, 50.00, NULL, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- LATE_PAYMENT_FEE: Flat INR 500, GST applicable (18%)
INSERT INTO charge_config (tenant_id, charge_code, charge_name, event_trigger, calculation_type, base_amount, gst_applicable, gst_rate, gl_charge_income, gl_gst_payable, waiver_allowed, max_waiver_percent, product_code, is_active, version, created_at, created_by)
VALUES ('DEFAULT', 'LATE_PAYMENT_FEE', 'Late Payment Fee', 'OVERDUE_EMI', 'FLAT', 500.00, 1, 18.00, '4002', '2200', 1, 100.00, NULL, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- STAMP_DUTY: Slab-based (no GST), ranges by loan amount
INSERT INTO charge_config (tenant_id, charge_code, charge_name, event_trigger, calculation_type, slab_json, gst_applicable, gl_charge_income, waiver_allowed, max_waiver_percent, product_code, is_active, version, created_at, created_by)
VALUES ('DEFAULT', 'STAMP_DUTY', 'Stamp Duty (Slab)', 'DISBURSEMENT', 'SLAB', '[{"min":0,"max":100000,"rate":0.10},{"min":100001,"max":500000,"rate":0.15},{"min":500001,"max":10000000,"rate":0.20}]', 0, '4002', 0, 0.00, NULL, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- DOCUMENTATION_CHARGE: Flat INR 1000, GST applicable (18%)
INSERT INTO charge_config (tenant_id, charge_code, charge_name, event_trigger, calculation_type, base_amount, gst_applicable, gst_rate, gl_charge_income, gl_gst_payable, waiver_allowed, max_waiver_percent, product_code, is_active, version, created_at, created_by)
VALUES ('DEFAULT', 'DOCUMENTATION_CHARGE', 'Documentation Charge', 'DISBURSEMENT', 'FLAT', 1000.00, 1, 18.00, '4002', '2200', 1, 0.00, NULL, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- App Users
-- DEV: Uses {noop} prefix (plaintext) with DelegatingPasswordEncoder.
-- PROD: Must use {bcrypt} hashed passwords. Never deploy {noop} to production.
-- Password for all dev users: finvanta123
--
-- CBS Role Matrix (Finacle/Temenos standard):
--   MAKER   = Loan Officer (creates applications, customers, transactions)
--   CHECKER = Verification/Approval Officer (verifies and approves)
--   ADMIN   = Branch Manager (full access, EOD, system config)
--   AUDITOR = Internal Auditor (read-only audit trail access)
--
-- Maker-Checker Flow: maker1 creates → checker1 verifies → checker2 approves
-- (verifier and approver MUST be different users per RBI guidelines)

-- Makers (Loan Officers)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, mfa_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'maker1', '{noop}finvanta123', 'Rajiv Menon (Loan Officer)', 'maker1@finvanta.com', 'MAKER', 1, 0, 0, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, mfa_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'maker2', '{noop}finvanta123', 'Sneha Iyer (Loan Officer)', 'maker2@finvanta.com', 'MAKER', 1, 0, 0, 2, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Checkers (Verification & Approval Officers)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, mfa_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'checker1', '{noop}finvanta123', 'Amit Deshmukh (Verification Officer)', 'checker1@finvanta.com', 'CHECKER', 1, 0, 0, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, mfa_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'checker2', '{noop}finvanta123', 'Kavita Nair (Approval Officer)', 'checker2@finvanta.com', 'CHECKER', 1, 0, 0, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Admin (Branch Manager) — MFA required per RBI IT Governance Direction 2023 (enrollment pending: no MFA endpoints yet)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, mfa_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'admin', '{noop}finvanta123', 'Vikram Joshi (Branch Manager)', 'admin@finvanta.com', 'ADMIN', 1, 0, 0, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Auditor (Internal Audit)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, mfa_enabled, version, created_at, created_by)
VALUES ('DEFAULT', 'auditor1', '{noop}finvanta123', 'Meera Kulkarni (Internal Auditor)', 'auditor@finvanta.com', 'AUDITOR', 1, 0, 0, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Transaction Limits (CBS Internal Controls -- per-role amount limits)
-- Per RBI guidelines: every financial transaction must be validated against configured limits
-- MAKER: INR 10L per transaction, INR 50L daily aggregate
-- CHECKER: INR 50L per transaction, INR 2Cr daily aggregate
-- ADMIN: INR 5Cr per transaction, INR 20Cr daily aggregate
INSERT INTO transaction_limits (tenant_id, role, transaction_type, per_transaction_limit, daily_aggregate_limit, is_active, description, version, created_at, created_by)
VALUES ('DEFAULT', 'MAKER', 'ALL', 1000000.00, 5000000.00, 1, 'Maker default limit: INR 10L per txn, INR 50L daily', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO transaction_limits (tenant_id, role, transaction_type, per_transaction_limit, daily_aggregate_limit, is_active, description, version, created_at, created_by)
VALUES ('DEFAULT', 'CHECKER', 'ALL', 5000000.00, 20000000.00, 1, 'Checker default limit: INR 50L per txn, INR 2Cr daily', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO transaction_limits (tenant_id, role, transaction_type, per_transaction_limit, daily_aggregate_limit, is_active, description, version, created_at, created_by)
VALUES ('DEFAULT', 'ADMIN', 'ALL', 50000000.00, 200000000.00, 1, 'Admin default limit: INR 5Cr per txn, INR 20Cr daily', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO transaction_limits (tenant_id, role, transaction_type, per_transaction_limit, daily_aggregate_limit, is_active, description, version, created_at, created_by)
VALUES ('DEFAULT', 'MAKER', 'WRITE_OFF', 0.00, 0.00, 1, 'Makers cannot perform write-offs (enforced via limit=0)', 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- ============================================================
-- E2E TEST SCENARIO SEED DATA
-- ============================================================
-- Per Tier-1 CBS architecture: transactional data (GL postings, ledger entries,
-- loan accounts with balances) MUST be created through the TransactionEngine
-- to maintain double-entry integrity, ledger hash chain, and audit trail.
--
-- What we CAN safely seed in data.sql (master/config data only):
--   1. April 1 as DAY_OPEN — so transactions work immediately after login
--   2. Default transaction batch — required by TransactionEngine Step 5.5
--   3. CASA deposit accounts — ACTIVE accounts for CUST001 and CUST002
--      (balance=0, will be funded via UI deposit after login)
--   4. Loan application (APPROVED) — ready for account creation + disbursement
--
-- What MUST be done via UI after startup (creates proper GL/ledger/audit):
--   Step 1: Login as admin → Dashboard shows April 1 as business date
--   Step 2: Login as maker1 → Deposit INR 500,000 into CUST001 CASA
--   Step 3: Login as maker1 → Deposit INR 1,000,000 into CUST002 CASA
--   Step 4: Login as checker1 → Create loan account from APP001
--   Step 5: Login as checker1 → Disburse loan (credits CUST001 CASA)
--   Step 6: Login as admin → Run EOD for April 1
--   Step 7: Login as admin → Close Day April 1 → Open Day April 2
--   Step 8: Repeat transactions → Run EOD → observe DPD, accrual, NPA
-- ============================================================

-- 1. Open April 1 as business day at ALL branches (so system is immediately usable)
-- Per Finacle DAYCTRL: all operational branches must have DAY_OPEN for EOD to run.
UPDATE business_calendar
SET day_status = 'DAY_OPEN', day_opened_by = 'SYSTEM', day_opened_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'DEFAULT' AND business_date = '2026-04-01' AND is_holiday = 0;

-- 2. Default transaction batches for April 1 — one per branch (required by TransactionEngine Step 5.5)
-- Per Finacle BATCH_MASTER / BusinessDateService.openDay(): when a day is opened,
-- a default INTRA_DAY batch is auto-created in OPEN status for each branch.
-- Status: OPEN — matches the realistic CBS state after Day Open.
-- CBS Workflow: Day OPEN + Batch OPEN → transactions → close batch → run EOD trial → apply EOD.
-- The ADMIN must close all batches via Batch Management before EOD trial will pass.
INSERT INTO transaction_batches (tenant_id, business_date, batch_name, batch_type, status,
    opened_by, opened_at, maker_id,
    total_transactions, total_debit, total_credit,
    version, created_at, created_by, branch_id)
VALUES ('DEFAULT', '2026-04-01', 'DEFAULT_BATCH_HQ001', 'INTRA_DAY', 'OPEN',
    'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM',
    0, 0.00, 0.00,
    0, CURRENT_TIMESTAMP, 'SYSTEM', 1);

INSERT INTO transaction_batches (tenant_id, business_date, batch_name, batch_type, status,
    opened_by, opened_at, maker_id,
    total_transactions, total_debit, total_credit,
    version, created_at, created_by, branch_id)
VALUES ('DEFAULT', '2026-04-01', 'DEFAULT_BATCH_DEL001', 'INTRA_DAY', 'OPEN',
    'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM',
    0, 0.00, 0.00,
    0, CURRENT_TIMESTAMP, 'SYSTEM', 2);

INSERT INTO transaction_batches (tenant_id, business_date, batch_name, batch_type, status,
    opened_by, opened_at, maker_id,
    total_transactions, total_debit, total_credit,
    version, created_at, created_by, branch_id)
VALUES ('DEFAULT', '2026-04-01', 'DEFAULT_BATCH_BLR001', 'INTRA_DAY', 'OPEN',
    'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM',
    0, 0.00, 0.00,
    0, CURRENT_TIMESTAMP, 'SYSTEM', 3);

-- 3. CASA Savings Accounts for KYC-verified customers (ACTIVE, zero balance)
-- Per CBS: accounts start at zero. Initial deposit must go through TransactionEngine
-- via the Deposit UI to create proper GL entries (DR Bank Ops / CR SB Deposits).
-- CUST001 (Rajesh Sharma) — Savings at HQ001
INSERT INTO deposit_accounts (tenant_id, account_number, customer_id, branch_id,
    account_type, product_code, currency_code, account_status,
    available_balance, ledger_balance, hold_amount, uncleared_amount,
    od_limit, minimum_balance, interest_rate, accrued_interest,
    ytd_interest_credited, ytd_tds_deducted,
    opened_date, last_transaction_date,
    cheque_book_enabled, debit_card_enabled,
    version, created_at, created_by, updated_by)
VALUES ('DEFAULT', 'SB-HQ001-000001', 1, 1,
    'SAVINGS', 'SAVINGS', 'INR', 'ACTIVE',
    0.00, 0.00, 0.00, 0.00,
    0.00, 5000.00, 4.0000, 0.00,
    0.00, 0.00,
    '2026-04-01', '2026-04-01',
    0, 0,
    0, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM');

-- CUST002 (Priya Patel) — Savings at DEL001
INSERT INTO deposit_accounts (tenant_id, account_number, customer_id, branch_id,
    account_type, product_code, currency_code, account_status,
    available_balance, ledger_balance, hold_amount, uncleared_amount,
    od_limit, minimum_balance, interest_rate, accrued_interest,
    ytd_interest_credited, ytd_tds_deducted,
    opened_date, last_transaction_date,
    cheque_book_enabled, debit_card_enabled,
    version, created_at, created_by, updated_by)
VALUES ('DEFAULT', 'SB-DEL001-000001', 2, 2,
    'SAVINGS', 'SAVINGS', 'INR', 'ACTIVE',
    0.00, 0.00, 0.00, 0.00,
    0.00, 5000.00, 4.0000, 0.00,
    0.00, 0.00,
    '2026-04-01', '2026-04-01',
    0, 0,
    0, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM');

-- 4. Loan Application (APPROVED) — ready for account creation + disbursement
-- CUST001 applies for Term Loan INR 500,000 at 12% for 24 months
-- Status: APPROVED (checker has already approved, ready for account creation)
-- Disbursement account: CUST001's CASA (SB-HQ001-000001)
INSERT INTO loan_applications (tenant_id, application_number, customer_id, branch_id,
    product_type, requested_amount, approved_amount, interest_rate, penal_rate,
    tenure_months, application_date, status,
    disbursement_account_number,
    version, created_at, created_by, updated_by)
VALUES ('DEFAULT', 'APP-HQ001-000001', 1, 1,
    'TERM_LOAN', 500000.00, 500000.00, 12.0000, 2.0000,
    24, '2026-04-01', 'APPROVED',
    'SB-HQ001-000001',
    0, CURRENT_TIMESTAMP, 'maker1', 'checker1');

-- 5. Second Loan Application (SUBMITTED) — for testing verification flow
-- CUST002 applies for Home Loan INR 2,500,000 at 8.5% for 120 months
-- Status: SUBMITTED (needs verification + approval before account creation)
INSERT INTO loan_applications (tenant_id, application_number, customer_id, branch_id,
    product_type, requested_amount, interest_rate, penal_rate,
    tenure_months, application_date, status,
    disbursement_account_number,
    version, created_at, created_by, updated_by)
VALUES ('DEFAULT', 'APP-DEL001-000001', 2, 2,
    'HOME_LOAN', 2500000.00, 8.5000, 2.0000,
    120, '2026-04-01', 'SUBMITTED',
    'SB-DEL001-000001',
    0, CURRENT_TIMESTAMP, 'maker2', 'maker2');
