-- ============================================================
-- Finvanta CBS - Seed Data for Development
-- Run after DDL or use with H2 auto-schema
-- ============================================================

-- 1. TENANT
INSERT INTO tenants (tenant_code, tenant_name, license_type, is_active, db_schema, created_by)
VALUES ('DEFAULT', 'Finvanta Demo Bank', 'ENTERPRISE', 1, 'public', 'SYSTEM');

-- 2. BRANCHES
INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, version, created_by)
VALUES ('DEFAULT', 'HQ001', 'Head Office', 'FNVT0000001', 'Bandra Kurla Complex', 'Mumbai', 'Maharashtra', '400051', 1, 'WEST', 0, 'SYSTEM');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, version, created_by)
VALUES ('DEFAULT', 'DEL001', 'New Delhi Main', 'FNVT0000002', 'Connaught Place', 'New Delhi', 'Delhi', '110001', 1, 'NORTH', 0, 'SYSTEM');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, version, created_by)
VALUES ('DEFAULT', 'BLR001', 'Bangalore Main', 'FNVT0000003', 'MG Road', 'Bangalore', 'Karnataka', '560001', 1, 'SOUTH', 0, 'SYSTEM');

-- 3. BUSINESS CALENDAR (April 2026)
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, is_eod_complete, is_locked, version, created_by)
VALUES ('DEFAULT', '2026-04-01', 0, 0, 0, 0, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, is_eod_complete, is_locked, version, created_by)
VALUES ('DEFAULT', '2026-04-02', 0, 0, 0, 0, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, is_eod_complete, is_locked, version, created_by)
VALUES ('DEFAULT', '2026-04-03', 0, 0, 0, 0, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, is_eod_complete, is_locked, version, created_by)
VALUES ('DEFAULT', '2026-04-04', 1, 0, 0, 0, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, is_eod_complete, is_locked, version, created_by)
VALUES ('DEFAULT', '2026-04-05', 1, 0, 0, 0, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, is_eod_complete, is_locked, version, created_by)
VALUES ('DEFAULT', '2026-04-06', 0, 0, 0, 0, 'SYSTEM');

-- 4. CUSTOMERS
INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, kyc_verified_date, kyc_verified_by, cibil_score, customer_type, is_active, branch_id, version, created_by)
VALUES ('DEFAULT', 'CUST001', 'Rajesh', 'Sharma', '1985-03-15', 'ABCDE1234F', '123456789012', '9876543210', 'rajesh.sharma@email.com', '123 MG Road', 'Mumbai', 'Maharashtra', '400001', 1, '2026-01-15', 'admin', 750, 'INDIVIDUAL', 1, 1, 0, 'SYSTEM');

INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, kyc_verified_date, kyc_verified_by, cibil_score, customer_type, is_active, branch_id, version, created_by)
VALUES ('DEFAULT', 'CUST002', 'Priya', 'Patel', '1990-07-22', 'FGHIJ5678K', '234567890123', '9876543211', 'priya.patel@email.com', '456 Ring Road', 'Delhi', 'Delhi', '110002', 1, '2026-02-10', 'admin', 820, 'INDIVIDUAL', 1, 2, 0, 'SYSTEM');

INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, cibil_score, customer_type, is_active, branch_id, version, created_by)
VALUES ('DEFAULT', 'CUST003', 'Arun', 'Kumar', '1978-11-05', 'KLMNO9012P', '345678901234', '9876543212', 'arun.kumar@email.com', '789 Brigade Road', 'Bangalore', 'Karnataka', '560002', 0, 580, 'INDIVIDUAL', 1, 3, 0, 'SYSTEM');

-- 5. GL MASTER (Chart of Accounts)
-- Assets
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '1000', 'Assets', 'ASSET', 0.00, 0.00, 1, 1, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '1001', 'Loan Portfolio - Term Loans', 'ASSET', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '1002', 'Interest Receivable', 'ASSET', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '1003', 'Provision for NPA', 'ASSET', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

-- Bank / Cash
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '1100', 'Bank Account - Operations', 'ASSET', 10000000.00, 0.00, 1, 0, 0, 'SYSTEM');

-- Liabilities
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '2000', 'Liabilities', 'LIABILITY', 0.00, 0.00, 1, 1, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '2001', 'Customer Deposits', 'LIABILITY', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

-- Equity
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '3000', 'Equity', 'EQUITY', 0.00, 0.00, 1, 1, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '3001', 'Share Capital', 'EQUITY', 0.00, 10000000.00, 1, 0, 0, 'SYSTEM');

-- Income
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '4000', 'Income', 'INCOME', 0.00, 0.00, 1, 1, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '4001', 'Interest Income - Loans', 'INCOME', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '4002', 'Fee Income', 'INCOME', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

-- Expenses
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '5000', 'Expenses', 'EXPENSE', 0.00, 0.00, 1, 1, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '5001', 'Provision Expense', 'EXPENSE', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_by)
VALUES ('DEFAULT', '5002', 'Write-Off Expense', 'EXPENSE', 0.00, 0.00, 1, 0, 0, 'SYSTEM');

-- 6. APP USERS (password: finvanta123 -> BCrypt hash)
-- Password BCrypt hash for 'finvanta123': $2a$12$LJ3m4ys3uz5s8v8ERFaBWeNz1gIyvnFBN4Bfazp5mvUDkP7ss3F3G
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_by)
VALUES ('DEFAULT', 'maker1', '$2a$12$LJ3m4ys3uz5s8v8ERFaBWeNz1gIyvnFBN4Bfazp5mvUDkP7ss3F3G', 'Maker User One', 'maker1@finvanta.com', 'MAKER', 1, 0, 0, 1, 0, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_by)
VALUES ('DEFAULT', 'checker1', '$2a$12$LJ3m4ys3uz5s8v8ERFaBWeNz1gIyvnFBN4Bfazp5mvUDkP7ss3F3G', 'Checker User One', 'checker1@finvanta.com', 'CHECKER', 1, 0, 0, 1, 0, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_by)
VALUES ('DEFAULT', 'admin', '$2a$12$LJ3m4ys3uz5s8v8ERFaBWeNz1gIyvnFBN4Bfazp5mvUDkP7ss3F3G', 'System Admin', 'admin@finvanta.com', 'ADMIN', 1, 0, 0, 1, 0, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_by)
VALUES ('DEFAULT', 'auditor1', '$2a$12$LJ3m4ys3uz5s8v8ERFaBWeNz1gIyvnFBN4Bfazp5mvUDkP7ss3F3G', 'Auditor User', 'auditor@finvanta.com', 'AUDITOR', 1, 0, 0, 1, 0, 'SYSTEM');
