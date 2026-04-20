-- ============================================================
-- Finvanta CBS — Migration V3e: AML Monitoring Rules Seed Data
-- Per PMLA 2002, RBI KYC Master Direction 2016 Sections 28-32,
-- and FATF Recommendation 20 (Suspicious Transaction Reporting).
--
-- Default monitoring rules for transaction pattern detection.
-- Banks must customize thresholds based on their risk appetite.
-- Run AFTER migration-v3-aml-cft.sql.
-- ============================================================

-- Rule 1: Structuring (Smurfing) Detection
-- Per PMLA: splitting transactions to avoid CTR threshold (10L)
INSERT INTO aml_monitoring_rules (tenant_id, rule_code, rule_name, rule_category,
    description, threshold_amount, threshold_count, time_window_hours,
    risk_score_impact, is_active, auto_str, version, created_at, created_by)
SELECT 'DEFAULT', 'STRUCT_CASH_24H', 'Cash Structuring — 24 Hour Window', 'STRUCTURING',
    'Multiple cash transactions from same customer within 24 hours that individually fall below 10L but aggregate above 10L. Per PMLA: designed to evade CTR reporting threshold.',
    900000.00, 3, 24, 25, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM aml_monitoring_rules WHERE tenant_id='DEFAULT' AND rule_code='STRUCT_CASH_24H');

-- Rule 2: Rapid Movement of Funds
-- Per RBI KYC MD: funds credited and immediately withdrawn
INSERT INTO aml_monitoring_rules (tenant_id, rule_code, rule_name, rule_category,
    description, threshold_amount, threshold_count, time_window_hours,
    risk_score_impact, is_active, auto_str, version, created_at, created_by)
SELECT 'DEFAULT', 'RAPID_MOVEMENT', 'Rapid Fund Movement', 'VELOCITY',
    'Large credit followed by immediate withdrawal within 4 hours. Indicates pass-through account usage for layering.',
    500000.00, 2, 4, 20, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM aml_monitoring_rules WHERE tenant_id='DEFAULT' AND rule_code='RAPID_MOVEMENT');

-- Rule 3: Dormant Account Reactivation with High Value
-- Per RBI KYC MD Section 38: dormant accounts reactivated with large txns
INSERT INTO aml_monitoring_rules (tenant_id, rule_code, rule_name, rule_category,
    description, threshold_amount, threshold_count, time_window_hours,
    risk_score_impact, is_active, auto_str, version, created_at, created_by)
SELECT 'DEFAULT', 'DORMANT_REACTIVATION', 'Dormant Account High-Value Reactivation', 'DORMANT',
    'Dormant/inoperative account reactivated with transaction exceeding 5L. Per RBI: requires enhanced monitoring.',
    500000.00, 1, 720, 30, 1, 1, 0, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM aml_monitoring_rules WHERE tenant_id='DEFAULT' AND rule_code='DORMANT_REACTIVATION');

-- Rule 4: High-Value Cash Deposit (non-business account)
-- Per RBI: cash deposits in individual savings accounts above 5L
INSERT INTO aml_monitoring_rules (tenant_id, rule_code, rule_name, rule_category,
    description, threshold_amount, threshold_count, time_window_hours,
    risk_score_impact, is_active, auto_str, version, created_at, created_by)
SELECT 'DEFAULT', 'HIGH_CASH_INDIVIDUAL', 'High Cash Deposit — Individual Account', 'HIGH_VALUE',
    'Cash deposit exceeding 5L in individual savings account. Per RBI: requires source of funds verification.',
    500000.00, 1, 24, 15, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM aml_monitoring_rules WHERE tenant_id='DEFAULT' AND rule_code='HIGH_CASH_INDIVIDUAL');

-- Rule 5: Velocity Check — Unusual Transaction Count
-- Per FATF Rec 20: unusual number of transactions in short period
INSERT INTO aml_monitoring_rules (tenant_id, rule_code, rule_name, rule_category,
    description, threshold_amount, threshold_count, time_window_hours,
    risk_score_impact, is_active, auto_str, version, created_at, created_by)
SELECT 'DEFAULT', 'VELOCITY_COUNT', 'Unusual Transaction Velocity', 'VELOCITY',
    'More than 10 transactions from same account within 2 hours. Indicates potential automated layering.',
    0.00, 10, 2, 20, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM aml_monitoring_rules WHERE tenant_id='DEFAULT' AND rule_code='VELOCITY_COUNT');

-- Rule 6: Round Amount Transactions (structuring indicator)
-- Per FATF: repeated round-amount transactions are a structuring indicator
INSERT INTO aml_monitoring_rules (tenant_id, rule_code, rule_name, rule_category,
    description, threshold_amount, threshold_count, time_window_hours,
    risk_score_impact, is_active, auto_str, version, created_at, created_by)
SELECT 'DEFAULT', 'ROUND_AMOUNTS', 'Repeated Round Amount Transactions', 'PATTERN',
    'Multiple transactions of exact round amounts (e.g., 1L, 2L, 5L, 9L) within 48 hours. Structuring indicator.',
    100000.00, 4, 48, 15, 1, 0, 0, CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM aml_monitoring_rules WHERE tenant_id='DEFAULT' AND rule_code='ROUND_AMOUNTS');
