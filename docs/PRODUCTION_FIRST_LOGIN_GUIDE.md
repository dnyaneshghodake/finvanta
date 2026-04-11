# Finvanta CBS — Production First Login & Day Zero Guide

**Version:** 1.0 | **Classification:** INTERNAL — Operations & DBA Team Only
**Per:** Finacle Day Zero / Temenos Installation / RBI IT Governance Direction 2023

---

## 1. Overview

In production, **`data.sql` is NEVER loaded**. The database starts empty.

The `CbsBootstrapInitializer` performs a **complete Day Zero installation**
on first startup when `app_users` is empty — per Finacle `INSTALL_BANK`:

| Step | What's Auto-Created |
|------|-------------------|
| 1 | Tenant record (bank identity per RBI) |
| 2 | Head Office branch (HQ001) |
| 3 | First Operational Branch (BR001) |
| 4 | GL Chart of Accounts (28 Indian Banking Standard codes) |
| 5 | ADMIN user with branch assigned + password expired |
| 6 | Business calendar for current month |
| 7 | First business day opened (weekdays only) |

Credentials are printed to **console stdout only** (never to log files per RBI §8.2).

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

## 4. Seed Data

**No manual seed data required.** The Day Zero bootstrap (§1) auto-creates:
- Tenant, Head Office, Operational Branch, GL Chart (28 accounts),
  Admin user, Calendar, and opens the first business day.

If you need to customize the bootstrap data (e.g., different bank name,
branch codes, IFSC prefix), set environment variables before first startup
or update the records via SSMS after bootstrap completes.

---

## 5. First Startup

```bash
java -jar finvanta-0.0.1-SNAPSHOT.war
```

Watch console for the Day Zero installation summary:
```
==============================================================
  CBS DAY ZERO: Installation Complete
==============================================================
  Tenant   : DEFAULT
  HO Branch: HQ001 — Head Office (Bootstrap)
  Op Branch: BR001 — Main Branch (Bootstrap)
  GL Codes : 28 accounts (Indian Banking Standard)
  Calendar : 22 entries for current month
  Day Open : 2026-04-15
--------------------------------------------------------------
  Username : sysadmin
  Password : xK7#mP2$nR9&vQ4!
  Role     : ADMIN
  Branch   : HQ001
--------------------------------------------------------------
  PASSWORD CHANGE REQUIRED ON FIRST LOGIN
  System is ready — login at /login
==============================================================
```

**⚠️ Copy the password NOW. It is shown ONCE and never again.**

No manual DBA steps required — the admin user already has a branch assigned,
GL chart is seeded, calendar is generated, and the business day is open.

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
        Branch: HQ001 | Date: 15-Apr-2026 | Role: ADMIN
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
