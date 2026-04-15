# Finvanta CBS — Product Configuration Guide

**Quick Reference for Branch Operations & Admin Staff**

Per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG / RBI Fair Practices Code 2023

---

## 1. Overview

Product Master is the central configuration that controls how every loan and deposit account
behaves — interest rates, GL codes, limits, and fees. No financial transaction can execute
without a configured product.

**Access:** Login as ADMIN → Admin → Product Master → Create Product

---

## 2. Product Categories

| Category | Code | Purpose | Examples |
|----------|------|---------|----------|
| Term Loan | `TERM_LOAN` | EMI-based loans with fixed tenure | Personal Loan, Vehicle Loan |
| Demand Loan | `DEMAND_LOAN` | Bullet/on-demand repayment | Gold Loan, Crop Loan |
| Overdraft | `OVERDRAFT` | Revolving credit against collateral | OD against FD, OD against Property |
| Cash Credit | `CASH_CREDIT` | Working capital facility for businesses | CC against Stock, CC against Debtors |
| CASA Savings | `CASA_SAVINGS` | Interest-bearing savings accounts | Regular SB, PMJDY, NRI SB |
| CASA Current | `CASA_CURRENT` | Zero-interest business accounts | Business CA |
| Term Deposit | `TERM_DEPOSIT` | Fixed deposits with maturity | Regular FD, Senior FD, Tax Saver |

> **Category is immutable after creation.** It determines GL accounting semantics (ASSET vs LIABILITY)
> for the product's entire lifecycle. To change category, create a new product and retire the old one.

---

## 3. Product Lifecycle

```
DRAFT ──→ ACTIVE ──→ SUSPENDED ──→ RETIRED
                  └──→ RETIRED
```

| Status | New Accounts | Existing Accounts | EOD Ops | Editable |
|--------|:---:|:---:|:---:|:---:|
| DRAFT | No | — | No | Yes |
| ACTIVE | Yes | Yes | Yes | Yes |
| SUSPENDED | No | Yes | Yes | Yes |
| RETIRED | No | Yes (run to maturity) | Yes | No |

---

## 4. Field Reference

### 4.1 Product Identity (Immutable after creation)

| Field | Required | Description |
|-------|:---:|-------------|
| Product Code | Yes | Uppercase alphanumeric + underscore, 2-50 chars (e.g., `VEHICLE_LOAN`) |
| Category | Yes | Determines GL routing and account behavior |
| Currency | Yes | INR (default), USD, EUR, GBP per ISO 4217 |

### 4.2 Product Identity (Mutable)

| Field | Required | Description |
|-------|:---:|-------------|
| Product Name | Yes | Display name (e.g., `Vehicle Loan - Secured`) |
| Description | No | Free text explanation of the product |

### 4.3 Interest Configuration

| Field | Required | Description |
|-------|:---:|-------------|
| Interest Method | Yes | `Actual/365` (RBI standard), `Actual/360`, `Actual/Actual`, `30/360` |
| Interest Type | Yes | `Fixed` or `Floating` (RBI EBLR-linked) |
| Min Interest Rate % | Yes | Floor rate. Must be >= 0 |
| Max Interest Rate % | Yes | Ceiling rate. Must be >= min rate, <= 100% |
| Default Penal Rate % | No | Overdue penalty rate. 0-36% per RBI usury ceiling |
| Repayment Frequency | Yes | `Monthly`, `Quarterly`, `Bullet`, `Maturity` |
| Repayment Allocation | No | `Interest First` (default), `Principal First`, `Pro-Rata` |
| Processing Fee % | No | Charged at disbursement. 0% for CASA products |
| Prepayment Penalty | No | Per RBI: not applicable for floating rate loans |

### 4.4 Floating Rate (RBI EBLR/MCLR Framework)

Only for products with Interest Type = Floating.

| Field | Description |
|-------|-------------|
| Benchmark | `EBLR`, `MCLR`, `RLLR`, `T-Bill`, or None for fixed |
| Reset Frequency | `Quarterly` (RBI minimum for EBLR), `Half Yearly`, `Yearly` |
| Default Spread % | Markup over benchmark (e.g., 2.50%) |

### 4.5 CASA Interest Tiering (Finacle INTDEF)

Only for CASA Savings products with balance-based slab rates.

| Field | Description |
|-------|-------------|
| CASA Tiering | Checkbox to enable balance-slab interest |
| Tiering JSON | Slab array: `[{"min":0,"max":100000,"rate":3.0},{"min":100001,"max":500000,"rate":3.5}]` |

### 4.6 Amount & Tenure Limits

| Field | Loan Products | CASA Products |
|-------|--------------|---------------|
| Min Amount | Min loan amount | Min balance (0 for PMJDY, 5000 for regular SB) |
| Max Amount | Max loan amount | 0 = unlimited (standard for CASA) |
| Min Tenure | Min loan months | 0 (CASA has no tenure) |
| Max Tenure | Max loan months | 0 (CASA has no tenure) |

### 4.7 GL Code Mapping (10 mandatory fields)

Each GL code is validated against `gl_master` for existence, active status, and correct account type.
**GL type rules are category-aware** — CASA/FD products use LIABILITY/EXPENSE GLs where loan products use ASSET/INCOME.

#### Loan Products (TERM_LOAN, DEMAND_LOAN, OVERDRAFT, CASH_CREDIT)

| GL Field | Label | Expected Type | Default GL |
|----------|-------|:---:|:---:|
| glLoanAsset | Loan Asset | ASSET | 1001 |
| glInterestReceivable | Interest Receivable | ASSET | 1002 |
| glBankOperations | Bank Operations | ASSET | 1100 |
| glInterestIncome | Interest Income | INCOME | 4001 |
| glFeeIncome | Fee Income | INCOME | 4002 |
| glPenalIncome | Penal Income | INCOME | 4003 |
| glProvisionExpense | Provision Expense | EXPENSE | 5001 |
| glProvisionNpa | Provision NPA | ASSET | 1003 |
| glWriteOffExpense | Write-Off Expense | EXPENSE | 5002 |
| glInterestSuspense | Interest Suspense | LIABILITY | 2100 |

#### CASA Products (CASA_SAVINGS, CASA_CURRENT)

| GL Field | Label | Expected Type | Savings (SB) | Current (CA) |
|----------|-------|:---:|:---:|:---:|
| glLoanAsset | Deposit Liability | LIABILITY | 2010 | 2020 |
| glInterestReceivable | Interest Expense | EXPENSE | 5010 | 5010 |
| glBankOperations | Bank Operations | ASSET | 1100 | 1100 |
| glInterestIncome | Interest Expense (P&L) | EXPENSE | 5010 | 5010 |
| glFeeIncome | Fee Income | INCOME | 4002 | 4002 |
| glPenalIncome | Penalty Charges | INCOME | 4003 | 4003 |
| glProvisionExpense | Interest Expense (Provision) | EXPENSE | 5010 | 5010 |
| glProvisionNpa | TDS Payable | LIABILITY | 2500 | 2500 |
| glWriteOffExpense | Closure/Write-Off Expense | EXPENSE | 5002 | 5002 |
| glInterestSuspense | Interest Suspense | LIABILITY | 2100 | 2100 |

#### Term Deposit Products (TERM_DEPOSIT)

| GL Field | Label | Expected Type | Default GL |
|----------|-------|:---:|:---:|
| glLoanAsset | FD Deposit Liability | LIABILITY | 2030 |
| glInterestReceivable | FD Interest Payable | LIABILITY | 2031 |
| glBankOperations | Bank Operations | ASSET | 1100 |
| glInterestIncome | FD Interest Expense (P&L) | EXPENSE | 5011 |
| glFeeIncome | Fee Income | INCOME | 4002 |
| glPenalIncome | Premature Penalty Income | INCOME | 4003 |
| glProvisionExpense | FD Interest Expense | EXPENSE | 5011 |
| glProvisionNpa | TDS Payable | LIABILITY | 2500 |
| glWriteOffExpense | Closure/Write-Off Expense | EXPENSE | 5002 |
| glInterestSuspense | Interest Suspense | LIABILITY | 2100 |

> **Key Difference:** Loan products use ASSET GLs for principal (bank owns the asset).
> Deposit products use LIABILITY GLs for principal (bank owes the depositor).
> The product-create form auto-switches labels and defaults when you change the category dropdown.

---

## 5. Product Examples

### 5.1 Term Loan — Vehicle Loan

```
Product Code:     VEHICLE_LOAN
Product Name:     Vehicle Loan - Secured
Category:         Term Loan
Currency:         INR
Method:           Actual/365
Type:             Fixed
Min Rate:         9.00%
Max Rate:         16.00%
Penal Rate:       2.00%
Frequency:        Monthly
Allocation:       Interest First
Processing Fee:   1.00%
Prepayment:       Yes (fixed rate)
Min Amount:       1,00,000
Max Amount:       25,00,000
Min Tenure:       12 months
Max Tenure:       60 months
GL Mapping:       1001 / 1002 / 1100 / 4001 / 4002 / 4003 / 5001 / 1003 / 5002 / 2100
```

### 5.2 Floating Rate — Home Loan (RBI EBLR)

```
Product Code:     HOME_LOAN
Product Name:     Home Loan - Secured
Category:         Term Loan
Currency:         INR
Method:           Actual/365
Type:             Floating
Min Rate:         6.50%
Max Rate:         12.00%
Penal Rate:       2.00%
Frequency:        Monthly
Prepayment:       No (floating rate — RBI prohibits penalty)
Benchmark:        EBLR
Reset Frequency:  Quarterly
Default Spread:   2.50%
Min Amount:       5,00,000
Max Amount:       5,00,00,000
Min Tenure:       12 months
Max Tenure:       360 months
GL Mapping:       1001 / 1002 / 1100 / 4001 / 4002 / 4003 / 5001 / 1003 / 5002 / 2100
```

### 5.3 CASA — Regular Savings Account

```
Product Code:     SAVINGS
Product Name:     Savings Account - Regular
Category:         CASA Savings
Currency:         INR
Method:           Actual/365
Type:             Fixed
Min Rate:         4.00%
Max Rate:         4.00%
Penal Rate:       0.00%
Frequency:        Quarterly (RBI: interest credited at quarter-end)
Processing Fee:   0.00%
Prepayment:       No
CASA Tiering:     No
Min Amount:       5,000 (minimum balance)
Max Amount:       0 (unlimited)
Min Tenure:       0
Max Tenure:       0
GL Mapping:       2010 / 5010 / 1100 / 5010 / 4002 / 4003 / 5010 / 2500 / 5002 / 2100
```

### 5.4 CASA — PMJDY Zero-Balance Savings

Same as Regular Savings except:

```
Product Code:     SAVINGS_PMJDY
Product Name:     Savings - PMJDY (Zero Balance)
Min Amount:       0 (zero minimum balance per PMJDY guidelines)
```

### 5.5 CASA — NRI Savings (NRE/NRO)

Same as Regular Savings except:

```
Product Code:     SAVINGS_NRI
Product Name:     Savings - NRE/NRO
Min Rate:         3.50%
Max Rate:         3.50%
Min Amount:       10,000
```

### 5.6 CASA — Business Current Account

```
Product Code:     CURRENT
Product Name:     Current Account - Business
Category:         CASA Current
Min Rate:         0.00% (zero interest per RBI)
Max Rate:         0.00%
Frequency:        Monthly (required field, not used)
Min Amount:       10,000
GL Mapping:       2020 / 5010 / 1100 / 5010 / 4002 / 4003 / 5010 / 2500 / 5002 / 2100
```

### 5.7 CASA — Premium Savings with Balance Tiering

Same as Regular Savings except:

```
Product Code:     SAVINGS_PREMIUM
Product Name:     Savings - Premium (Tiered Interest)
Min Rate:         3.00% (lowest slab)
Max Rate:         5.00% (highest slab)
CASA Tiering:     Yes
Tiering JSON:     [{"min":0,"max":100000,"rate":3.0},
                   {"min":100001,"max":500000,"rate":3.5},
                   {"min":500001,"max":1000000,"rate":4.0},
                   {"min":1000001,"max":99999999,"rate":5.0}]
Min Amount:       25,000
```

Tiered calculation example (balance = 7,00,000):

| Slab | Range | Applicable | Rate | Daily Interest |
|------|-------|-----------|------|---------------|
| 1 | 0 - 1,00,000 | 1,00,000 | 3.0% | 8.22 |
| 2 | 1,00,001 - 5,00,000 | 4,00,000 | 3.5% | 38.36 |
| 3 | 5,00,001 - 10,00,000 | 2,00,000 | 4.0% | 21.92 |
| **Total** | | **7,00,000** | | **68.50/day** |

Quarterly (90 days): 68.50 x 90 = **6,165.00**

### 5.8 Term Deposit — Regular FD

```
Product Code:     FD_REGULAR
Product Name:     Fixed Deposit - Regular
Category:         Term Deposit
Currency:         INR
Method:           Actual/365
Type:             Fixed
Min Rate:         5.50%
Max Rate:         7.50%
Penal Rate:       1.00% (premature withdrawal penalty)
Frequency:        Maturity
Prepayment:       Yes (premature withdrawal allowed with penalty)
Min Amount:       10,000 (minimum deposit)
Max Amount:       0 (unlimited)
Min Tenure:       0 (7 days minimum — handled at account level)
Max Tenure:       120 months (10 years)
GL Mapping:       2030 / 2031 / 1100 / 5011 / 4002 / 4003 / 5011 / 2030 / 5002 / 2100
```

### 5.9 Term Deposit — Tax Saver (IT Act Section 80C)

Same as Regular FD except:

```
Product Code:     FD_TAX_SAVER
Product Name:     Fixed Deposit - Tax Saver (80C)
Min Rate:         6.50%
Max Rate:         7.50%
Penal Rate:       0.00% (no premature withdrawal allowed)
Prepayment:       No (5-year lock-in per IT Act)
Max Amount:       1,50,000 (Section 80C annual limit)
Min Tenure:       60 months (5-year lock-in)
Max Tenure:       60 months
```

---

## 6. Server-Side Validation Rules

The following validations run on both Create and Edit operations:

| Rule | Validation |
|------|-----------|
| Product Code Format | Uppercase alphanumeric + underscore, 2-50 chars |
| Duplicate Check | Product code must be unique per tenant |
| Rate Range | 0 <= min rate <= max rate <= 100% |
| Penal Rate | 0% to 36% (RBI usury ceiling) |
| Amount Range | min amount <= max amount (max=0 means unlimited) |
| Tenure Range | min tenure <= max tenure |
| GL Existence | Each GL code must exist in gl_master |
| GL Active | Each GL code must be active and not a header account |
| GL Type Match | **Category-aware** — see Section 4.7 for type rules per category |
| Retired Block | RETIRED products cannot be edited |
| Category Immutable | Product category cannot be changed after creation |

**Category-aware GL validation:** The expected GL account type for each field depends on the
product category. For example, `glLoanAsset` must be ASSET for loan products but LIABILITY for
CASA/FD products. The product-create form auto-switches labels and defaults when the category
dropdown changes. On edit, the category is immutable and the correct validation rules are
applied automatically based on the existing product's category.

---

## 7. Maker-Checker for GL Code Changes

When GL codes are modified on a product that has **active accounts** (loan or deposit), the
change requires dual authorization per RBI Internal Controls:

```
Step 1: MAKER edits GL codes → system detects active accounts exist
Step 2: System creates PENDING_APPROVAL workflow → MAKER sees "pending approval" message
Step 3: CHECKER reviews GL diff on Approval Dashboard → Approves or Rejects
Step 4: MAKER re-submits the same edit → system finds approved workflow → applies change
Step 5: Approved workflow is consumed (one-time use, prevents replay)
```

**When maker-checker is NOT required:**
- Non-GL changes (name, rates, limits, description) — always apply immediately
- GL changes on products with **zero** active accounts — apply immediately
- New product creation — no existing accounts to affect

The GL diff is recorded in the workflow's `payloadSnapshot` for audit trail.

---

## 8. Product Cloning

Clone an existing product to create variants without re-entering all 30+ fields.

**Access:** Admin → Product Detail → Clone Product

```
Source Product:    HOME_LOAN (v3, ACTIVE, 245 accounts)
New Product Code:  HOME_LOAN_AFFORDABLE
New Product Name:  Home Loan - Affordable Housing
```

**What is copied:** All parameters — GL codes, rates, limits, floating config, tiering JSON.

**What is NOT copied:** Product code, name, description (set by admin). The clone gets
`configVersion=1`, independent lifecycle, and its own audit trail.

**Validation:** The clone's GL codes are re-validated against the source product's category.
If the source is CASA_SAVINGS, the clone inherits CASA_SAVINGS category and must have
LIABILITY/EXPENSE GLs.

---

## 9. Product-Driven GL Resolution

When a CASA transaction is posted (deposit, withdrawal, transfer, interest credit), the
system resolves GL codes from the account's product via `ProductGLResolver`:

```
1. Read account.productCode (e.g., "SAVINGS")
2. ProductGLResolver.getLoanAssetGL("SAVINGS") → looks up product_master cache
3. Returns product's glLoanAsset (e.g., "2010" = SB Deposits LIABILITY)
4. If product not found → falls back to GLConstants (hardcoded default)
```

**Impact:** Changing a GL code on a CASA product immediately affects all future transactions
on every account using that product. This is the core value of product-driven GL architecture.

**Fallback chain:** If `ProductGLResolver` returns the loan-module default (GL 1001 = Loan Asset),
the system detects this is wrong for CASA and falls back to type-based hardcoded GLs
(2010 for Savings, 2020 for Current).

---

## 10. Config Version Tracking

Every product edit increments `configVersion` (starting at 1 on creation). Combined with
the audit trail's before/after field snapshots, this provides a complete version history:

```
v1: Created by admin — TERM_LOAN, rate 8-14%, GL 1001/1002/...
v2: Updated by admin — rate changed to 8-16%
v3: Updated by admin — GL 1001 → 1010 (approved by checker)
```

The config version is displayed on the product detail page and in audit log entries.

---

## 11. Product Search

**Access:** Admin → Products → Search bar

Search by product code, name, category, or status. Minimum 2 characters.
Examples: `SAVINGS`, `TERM`, `ACTIVE`, `CASA`.

---

## 12. Important Notes

1. **GL Cache:** When GL codes are changed on a product, the system automatically evicts
   the ProductGLResolver cache. All future transactions will use the new GL codes.

2. **Active Accounts Warning:** The edit form shows the count of active loan + deposit
   accounts using this product. GL code changes affect all future transactions on these accounts.

3. **CASA Field Reinterpretation:** For CASA products, `Min Amount` = minimum balance and
   `Max Amount` = maximum balance (0 = unlimited). Tenure fields should be 0.

4. **Immutable Fields:** Product Code, Category, and Currency cannot be changed after creation.
   To change these, create a new product (or clone) and retire the old one.

5. **Floating Rate per RBI:** Since October 2019, all new floating rate retail loans must be
   linked to an external benchmark (EBLR). Prepayment penalty is prohibited on floating rate loans.

6. **Category-Aware GL Labels:** The product-create form dynamically switches GL field labels
   and default selections when the category dropdown changes. Loan products show "Loan Asset",
   "Interest Receivable", etc. CASA/FD products show "Deposit Liability", "Interest Expense",
   "TDS Payable", etc. This prevents GL misconfiguration.

7. **FD vs CASA GL Difference:** FD products use `glInterestReceivable` = FD Interest Payable
   (GL 2031, LIABILITY) representing accrued interest owed to the depositor. CASA products use
   `glInterestReceivable` = Interest Expense (GL 5010, EXPENSE) representing the P&L charge.
   This distinction is enforced by separate validation branches for CASA vs FD categories.

8. **Mass Assignment Protection:** On product creation, the system explicitly nullifies `id`
   and `version` fields to prevent OWASP A4 mass assignment attacks where a malicious POST
   could overwrite an existing product by injecting `id=<existing_id>`.

9. **Field-Level Audit Trail:** Every product edit records a complete before/after diff of
   all changed fields (rates, GL codes, limits, etc.) in the audit log per RBI IT Governance
   Direction 2023 §8.3. Auditors can reconstruct the exact state of any product at any point.

10. **Overdraft/Cash Credit:** These loan categories use the same ASSET GL validation as
    TERM_LOAN and DEMAND_LOAN. They differ in repayment behavior (revolving vs bullet) but
    share the same GL accounting semantics.
