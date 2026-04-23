-- ============================================================================
-- CBS Migration: Extended Account Opening Fields
-- Per Finvanta Account Opening API Contract v1.0 / RBI KYC Master Direction 2016
-- ============================================================================
-- Adds 22 new nullable columns to deposit_accounts for the full 29-field
-- account opening form. All columns are nullable for backward compatibility
-- with existing accounts opened before this migration.
--
-- PAN/Aadhaar columns use length 100 to accommodate AES-256-GCM ciphertext
-- (Base64-encoded IV + ciphertext) via PiiEncryptionConverter.
-- ============================================================================

-- §3 KYC & Regulatory
ALTER TABLE deposit_accounts ADD pan_number NVARCHAR(100) NULL;
ALTER TABLE deposit_accounts ADD aadhaar_number NVARCHAR(100) NULL;
ALTER TABLE deposit_accounts ADD kyc_status NVARCHAR(20) NULL;
ALTER TABLE deposit_accounts ADD pep_flag BIT NOT NULL DEFAULT 0;

-- §4 Personal Details
ALTER TABLE deposit_accounts ADD full_name NVARCHAR(200) NULL;
ALTER TABLE deposit_accounts ADD date_of_birth DATE NULL;
ALTER TABLE deposit_accounts ADD gender NVARCHAR(10) NULL;
ALTER TABLE deposit_accounts ADD father_spouse_name NVARCHAR(200) NULL;
ALTER TABLE deposit_accounts ADD nationality NVARCHAR(20) NULL;

-- §5 Contact Details
ALTER TABLE deposit_accounts ADD mobile_number NVARCHAR(15) NULL;
ALTER TABLE deposit_accounts ADD email NVARCHAR(200) NULL;

-- §6 Address
ALTER TABLE deposit_accounts ADD address_line1 NVARCHAR(500) NULL;
ALTER TABLE deposit_accounts ADD address_line2 NVARCHAR(500) NULL;
ALTER TABLE deposit_accounts ADD city NVARCHAR(100) NULL;
ALTER TABLE deposit_accounts ADD state NVARCHAR(100) NULL;
ALTER TABLE deposit_accounts ADD pin_code NVARCHAR(6) NULL;

-- §7 Occupation & Financial Profile
ALTER TABLE deposit_accounts ADD occupation NVARCHAR(30) NULL;
ALTER TABLE deposit_accounts ADD annual_income NVARCHAR(20) NULL;
ALTER TABLE deposit_accounts ADD source_of_funds NVARCHAR(30) NULL;

-- §9 FATCA / CRS
ALTER TABLE deposit_accounts ADD us_tax_resident BIT NOT NULL DEFAULT 0;

-- §10 Account Configuration
ALTER TABLE deposit_accounts ADD sms_alerts BIT NOT NULL DEFAULT 1;

-- Index for PEP flag — compliance team queries PEP accounts for enhanced monitoring
CREATE INDEX idx_depacc_tenant_pep ON deposit_accounts (tenant_id, pep_flag)
    WHERE pep_flag = 1;

-- Index for FATCA reporting — US tax resident accounts require annual FATCA filing
CREATE INDEX idx_depacc_tenant_fatca ON deposit_accounts (tenant_id, us_tax_resident)
    WHERE us_tax_resident = 1;
