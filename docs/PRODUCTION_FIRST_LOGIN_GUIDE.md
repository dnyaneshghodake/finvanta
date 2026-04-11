# Finvanta CBS — Production First Login & Day Zero Guide

**Version:** 1.0 | **Classification:** INTERNAL — Operations & DBA Team Only
**Per:** Finacle Day Zero / Temenos Installation / RBI IT Governance Direction 2023

---

## 1. Overview

In production, **`data.sql` is NEVER loaded**. The database starts empty.
There are **no users, no branches, no calendar, no GL accounts**.

The `CbsBootstrapInitializer` automatically creates the first ADMIN user
on startup when `app_users` is empty. It prints credentials to **console
stdout only** (never to log files per RBI §8.2).

---

## 2. Pre-Deployment: Environment Variables

```bash
# MANDATORY
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_URL="jdbc:sqlserver://host:1433;databaseName=finvanta;encrypt=true"
export SPRING_DATASOURCE_USERNAME=finvanta_app
export SPRING_DATASOURCE_PASSWORD=<from-vault>
export MFA_ENCRYPTION_KEY=$(openssl rand -hex 32)

# OPTIONAL — Customize bootstrap admin
export CBS_ADMIN_USERNAME=sysadmin           # Default: sysadmin
export CBS_ADMIN_PASSWORD=MyStr0ng!Pass#2026 # Default: auto-generated
export CBS_ADMIN_EMAIL=admin@yourbank.com    # Default: admin@localhost
export CBS_ADMIN_TENANT=DEFAULT              # Default: DEFAULT
```

---

## 3. Pre-Deployment: Database Schema

Prod uses `ddl-auto=validate` — tables must exist first.

**Option A — First run only:** Add `export SPRING_JPA_HIBERNATE_DDL_AUTO=update`,
start once, then remove the variable.

**Option B — DDL script:** Run app locally with `sqlserver` profile, export
schema via SSMS Generate Scripts, apply on production.

---

## 4. Pre-Deployment: Seed Data (DBA)

### 4.1 Tenant
```sql
INSERT INTO tenants (tenant_code, tenant_name, license_type, is_active, db_schema,
    rbi_bank_code, ifsc_prefix, license_number, regulatory_category,
    country_code, base_currency, timezone, created_at, created_by)
VALUES ('DEFAULT', 'Your Bank', 'ENTERPRISE', 1, 'dbo',
    '9999', 'XXXX', 'RBI/SCB/YYYY/XXX', 'SCB',
    'IN', 'INR', 'Asia/Kolkata', CURRENT_TIMESTAMP, 'DBA');
```

### 4.2 Branches
```sql
INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code,
    address, city, state, pin_code, is_active, zone_code,
    branch_type, is_head_office, region_code, state_code,
    version, created_at, created_by)
VALUES ('DEFAULT', 'HQ001', 'Head Office', 'XXXX0000001',
    'HO Address', 'Mumbai', 'Maharashtra', '400001', 1, 'WEST',
    'HEAD_OFFICE', 1, 'WEST', 'MH', 0, CURRENT_TIMESTAMP, 'DBA');

INSERT INTO branches (tenant_id, branch_code, branch_name, ifsc_code,
    address, city, state, pin_code, is_active, zone_code,
    branch_type, is_head_office, parent_branch_id, region_code, state_code,
    version, created_at, created_by)
VALUES ('DEFAULT', 'BR001', 'Main Branch', 'XXXX0000002',
    'Branch Address', 'Mumbai', 'Maharashtra', '400001', 1, 'WEST',
    'BRANCH', 0, 1, 'WEST', 'MH', 0, CURRENT_TIMESTAMP, 'DBA');
```

### 4.3 GL Chart of Accounts
```sql
INSERT INTO gl_master (tenant_id, gl_code, gl_name, account_type,
    debit_balance, credit_balance, is_active, is_header_account,
    version, created_at, created_by) VALUES
('DEFAULT','1000','Assets','ASSET',0,0,1,1,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','1001','Loan Portfolio','ASSET',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','1002','Interest Receivable','ASSET',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','1003','Provision for NPA','ASSET',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','1100','Bank Account - Ops','ASSET',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','1300','IB Receivable','ASSET',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2000','Liabilities','LIABILITY',0,0,1,1,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2001','Customer Deposits','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2010','Deposits - Savings','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2020','Deposits - Current','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2100','Interest Suspense','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2200','CGST Payable','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2201','SGST Payable','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2300','IB Payable','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2400','Clearing Suspense','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','2500','TDS Payable §194A','LIABILITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','3000','Equity','EQUITY',0,0,1,1,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','3001','Share Capital','EQUITY',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','4000','Income','INCOME',0,0,1,1,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','4001','Interest Income','INCOME',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','4002','Fee Income','INCOME',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','4003','Penal Interest','INCOME',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','4010','Interest Inc Dep','INCOME',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','5000','Expenses','EXPENSE',0,0,1,1,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','5001','Provision Expense','EXPENSE',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','5002','Write-Off Expense','EXPENSE',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA'),
('DEFAULT','5010','Interest Exp Dep','EXPENSE',0,0,1,0,0,CURRENT_TIMESTAMP,'DBA');
```

---

## 5. First Startup

```bash
java -jar finvanta-0.0.1-SNAPSHOT.war
```

Watch console for:
```
╔══════════════════════════════════════════════════════════════╗
║  CBS BOOTSTRAP: Initial Admin User Created                  ║
╠══════════════════════════════════════════════════════════════╣
║  Username : sysadmin                                        ║
║  Password : xK7#mP2$nR9&vQ4!                               ║
║  Tenant   : DEFAULT                                         ║
║  Role     : ADMIN                                           ║
╠══════════════════════════════════════════════════════════════╣
║  ⚠ PASSWORD CHANGE REQUIRED ON FIRST LOGIN                  ║
║  ⚠ NOTE: Branch must be assigned via DB before login         ║
╚══════════════════════════════════════════════════════════════╝
```

**⚠️ Copy the password NOW. It is shown ONCE and never again.**

### Assign Branch to Admin (MANDATORY before login)
```sql
UPDATE app_users SET branch_id = 1
WHERE tenant_id = 'DEFAULT' AND username = 'sysadmin';
```

---

## 6. First Login Flow

```
STEP 1: Open https://your-server:8080/login
STEP 2: Enter sysadmin / <bootstrap-password>
           ↓
STEP 3: FORCED PASSWORD CHANGE (password expired)
        → Enter current password
        → Enter new password (min 8 chars, upper+lower+digit+special)
        → Confirm new password
           ↓
STEP 4: SESSION INVALIDATED → Redirect to /login
        Message: "Password changed successfully"
           ↓
STEP 5: RE-LOGIN with NEW password
           ↓
STEP 6: DASHBOARD — Logged in as ADMIN
        Branch: HQ001 | Date: -- | Role: ADMIN
```

---

## 7. Day Zero Setup (After First Login)

| Order | Action | Where | Notes |
|-------|--------|-------|-------|
| 1 | Generate Calendar | Business Calendar | Year + Month for all branches |
| 2 | Add Holidays | Business Calendar | Gazetted holidays per NI Act |
| 3 | Open Business Day | Business Calendar | Auto-creates txn batches |
| 4 | Configure Limits | Transaction Limits | Per role: MAKER/CHECKER/ADMIN |
| 5 | Create Products | Product Master | TERM_LOAN, HOME_LOAN, SAVINGS, CURRENT |
| 6 | Configure Charges | Charge Config | Processing fee, late payment, stamp duty |
| 7 | Create Users | User Management | MAKER, CHECKER with branch assignments |
| 8 | Create Customers | Customers | KYC verification required |
| 9 | Open Accounts | CASA / Loan | First transactions |

---

## 8. Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| No bootstrap output on console | Users already exist in DB | Bootstrap runs ONCE only |
| `BRANCH_NOT_ASSIGNED` error | Admin has no branch_id | Run UPDATE SQL from §5 |
| `MFA encryption key is DEFAULT` | Missing MFA_ENCRYPTION_KEY | Set env var per §2 |
| `ddl-auto=validate` fails | Tables don't exist | Use Option A or B from §3 |
| Password change rejected | Doesn't meet complexity | Min 8, upper+lower+digit+special |
| Business date shows `--` | No day opened | Generate calendar + Day Open |
| `BATCH_NOT_OPEN` on transaction | No open batch for date | Day Open auto-creates batches |
| `GL account not found` | GL master not seeded | Run SQL from §4.3 |
| Login says "User not found" | Wrong tenant context | Check CBS_ADMIN_TENANT matches tenant_code |

---

## 9. Security Notes

- Bootstrap credentials are printed to **stdout only** — never to SLF4J/logback
- Password is bcrypt-hashed with `{bcrypt}` prefix (DelegatingPasswordEncoder)
- Password expiry is set to T-1 — forces immediate change per RBI §8.2
- MFA is disabled for bootstrap admin — enable via Admin → MFA Management
- After first login, create dedicated MAKER/CHECKER/AUDITOR users
- The `sysadmin` account should be used for initial setup only
- Per RBI: rotate the admin password every 90 days (auto-enforced)
