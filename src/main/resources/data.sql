-- ============================================================
-- Finvanta CBS - H2 Seed Data (auto-loaded by Spring Boot)
-- ============================================================

-- Tenant (created_at required — data.sql bypasses Hibernate @CreationTimestamp)
INSERT INTO tenants (tenant_code, tenant_name, license_type, is_active, db_schema, created_at, created_by)
VALUES ('DEFAULT', 'Finvanta Demo Bank', 'ENTERPRISE', true, 'public', CURRENT_TIMESTAMP, 'SYSTEM');

-- Branches
INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, version, created_at, created_by)
VALUES ('DEFAULT', 'HQ001', 'Head Office', 'FNVT0000001', 'Bandra Kurla Complex', 'Mumbai', 'Maharashtra', '400051', true, 'WEST', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, version, created_at, created_by)
VALUES ('DEFAULT', 'DEL001', 'New Delhi Main', 'FNVT0000002', 'Connaught Place', 'New Delhi', 'Delhi', '110001', true, 'NORTH', 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code, address, city, state, pin_code, is_active, region, version, created_at, created_by)
VALUES ('DEFAULT', 'BLR001', 'Bangalore Main', 'FNVT0000003', 'MG Road', 'Bangalore', 'Karnataka', '560001', true, 'SOUTH', 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Business Calendar (April 2026) — day_status required per CBS Day Control lifecycle
-- Holidays: Sat/Sun (4-5, 11-12, 18-19, 25-26), Ram Navami (6), Mahavir Jayanti (10), Good Friday (17), Dr Ambedkar Jayanti (14)
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-01', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-02', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-03', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-04', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-05', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-06', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-07', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-08', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-09', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-10', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-11', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-12', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-13', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-14', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-15', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-16', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-17', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-18', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-19', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-20', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-21', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-22', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-23', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-24', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-25', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-26', true,  'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-27', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-28', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-29', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO business_calendar (tenant_id, business_date, is_holiday, day_status, is_eod_complete, is_locked, version, created_at, created_by) VALUES ('DEFAULT', '2026-04-30', false, 'NOT_OPENED', false, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Customers
INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, kyc_verified_date, kyc_verified_by, cibil_score, customer_type, is_active, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'CUST001', 'Rajesh', 'Sharma', '1985-03-15', 'ABCDE1234F', '123456789012', '9876543210', 'rajesh.sharma@email.com', '123 MG Road', 'Mumbai', 'Maharashtra', '400001', true, '2026-01-15', 'admin', 750, 'INDIVIDUAL', true, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, kyc_verified_date, kyc_verified_by, cibil_score, customer_type, is_active, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'CUST002', 'Priya', 'Patel', '1990-07-22', 'FGHIJ5678K', '234567890123', '9876543211', 'priya.patel@email.com', '456 Ring Road', 'Delhi', 'Delhi', '110002', true, '2026-02-10', 'admin', 820, 'INDIVIDUAL', true, 2, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO customers (tenant_id, customer_number, first_name, last_name, date_of_birth, pan_number, aadhaar_number, mobile_number, email, address, city, state, pin_code, kyc_verified, cibil_score, customer_type, is_active, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'CUST003', 'Arun', 'Kumar', '1978-11-05', 'KLMNO9012P', '345678901234', '9876543212', 'arun.kumar@email.com', '789 Brigade Road', 'Bangalore', 'Karnataka', '560002', false, 580, 'INDIVIDUAL', true, 3, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Product Master (Finacle PDDEF / Temenos AA.PRODUCT.CATALOG)
-- Per CBS standards: GL codes are configured per product, not hardcoded.
INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, version, created_at, created_by)
VALUES ('DEFAULT', 'TERM_LOAN', 'Term Loan - Unsecured', 'TERM_LOAN', 'Standard unsecured term loan for salaried individuals', 'INR', 'ACTUAL_365', 'FIXED', 8.0000, 24.0000, 2.0000, 50000.00, 5000000.00, 6, 84, 'MONTHLY', '1001', '1002', '1100', '4001', '4002', '4003', '5001', '1003', '5002', '2100', true, 'INTEREST_FIRST', false, 1.0000, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, version, created_at, created_by)
VALUES ('DEFAULT', 'HOME_LOAN', 'Home Loan - Secured', 'TERM_LOAN', 'Housing finance for residential property purchase', 'INR', 'ACTUAL_365', 'FLOATING', 6.5000, 12.0000, 2.0000, 500000.00, 50000000.00, 12, 360, 'MONTHLY', '1001', '1002', '1100', '4001', '4002', '4003', '5001', '1003', '5002', '2100', true, 'INTEREST_FIRST', false, 0.5000, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO product_master (tenant_id, product_code, product_name, product_category, description, currency_code, interest_method, interest_type, min_interest_rate, max_interest_rate, default_penal_rate, min_loan_amount, max_loan_amount, min_tenure_months, max_tenure_months, repayment_frequency, gl_loan_asset, gl_interest_receivable, gl_bank_operations, gl_interest_income, gl_fee_income, gl_penal_income, gl_provision_expense, gl_provision_npa, gl_write_off_expense, gl_interest_suspense, is_active, repayment_allocation, prepayment_penalty_applicable, processing_fee_pct, version, created_at, created_by)
VALUES ('DEFAULT', 'GOLD_LOAN', 'Gold Loan - Secured', 'DEMAND_LOAN', 'Loan against gold ornaments with bullet repayment', 'INR', 'ACTUAL_365', 'FIXED', 7.0000, 15.0000, 2.0000, 10000.00, 2500000.00, 3, 12, 'BULLET', '1001', '1002', '1100', '4001', '4002', '4003', '5001', '1003', '5002', '2100', true, 'INTEREST_FIRST', true, 0.2500, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- GL Master (Chart of Accounts)
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1000', 'Assets', 'ASSET', 0.00, 0.00, true, true, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1001', 'Loan Portfolio - Term Loans', 'ASSET', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1002', 'Interest Receivable', 'ASSET', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1003', 'Provision for NPA', 'ASSET', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '1100', 'Bank Account - Operations', 'ASSET', 10000000.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2000', 'Liabilities', 'LIABILITY', 0.00, 0.00, true, true, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2001', 'Customer Deposits', 'LIABILITY', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2100', 'Interest Suspense - NPA', 'LIABILITY', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '2101', 'Sundry Suspense', 'LIABILITY', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '3000', 'Equity', 'EQUITY', 0.00, 0.00, true, true, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '3001', 'Share Capital', 'EQUITY', 0.00, 10000000.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4000', 'Income', 'INCOME', 0.00, 0.00, true, true, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4001', 'Interest Income - Loans', 'INCOME', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4002', 'Fee Income', 'INCOME', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '4003', 'Penal Interest Income', 'INCOME', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5000', 'Expenses', 'EXPENSE', 0.00, 0.00, true, true, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5001', 'Provision Expense', 'EXPENSE', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type, debit_balance, credit_balance, is_active, is_header_account, version, created_at, created_by) VALUES ('DEFAULT', '5002', 'Write-Off Expense', 'EXPENSE', 0.00, 0.00, true, false, 0, CURRENT_TIMESTAMP, 'SYSTEM');

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
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'maker1', '{noop}finvanta123', 'Rajiv Menon (Loan Officer)', 'maker1@finvanta.com', 'MAKER', true, false, 0, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'maker2', '{noop}finvanta123', 'Sneha Iyer (Loan Officer)', 'maker2@finvanta.com', 'MAKER', true, false, 0, 2, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Checkers (Verification & Approval Officers)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'checker1', '{noop}finvanta123', 'Amit Deshmukh (Verification Officer)', 'checker1@finvanta.com', 'CHECKER', true, false, 0, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'checker2', '{noop}finvanta123', 'Kavita Nair (Approval Officer)', 'checker2@finvanta.com', 'CHECKER', true, false, 0, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Admin (Branch Manager)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'admin', '{noop}finvanta123', 'Vikram Joshi (Branch Manager)', 'admin@finvanta.com', 'ADMIN', true, false, 0, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');

-- Auditor (Internal Audit)
INSERT INTO app_users (tenant_id, username, password_hash, full_name, email, role, is_active, is_locked, failed_login_attempts, branch_id, version, created_at, created_by)
VALUES ('DEFAULT', 'auditor1', '{noop}finvanta123', 'Meera Kulkarni (Internal Auditor)', 'auditor@finvanta.com', 'AUDITOR', true, false, 0, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM');
