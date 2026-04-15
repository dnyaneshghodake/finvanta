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
| Demand Loan | `DEMAND_LOAN` | Bullet/on-demand repayment | Gold Loan, Overdraft |
| CASA Savings | `CASA_SAVINGS` | Interest-bearing savings accounts | Regular SB, PMJDY, NRI SB |
| CASA Current | `CASA_CURRENT` | Zero-interest business accounts | Business CA |
| Term Deposit | `TERM_DEPOSIT` | Fixed deposits with maturity | Regular FD, Senior FD, Tax Saver |

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

| GL Field | Expected Type | Loan | CASA Savings | CASA Current |
|----------|:---:|:---:|:---:|:---:|
| Loan Asset | ASSET | 1001 | 2010 | 2020 |
| Interest Receivable | ASSET | 1002 | 2010 | 2020 |
| Bank Operations | ASSET | 1100 | 1100 | 1100 |
| Interest Income | INCOME | 4001 | 4010 | 4010 |
| Fee Income | INCOME | 4002 | 4002 | 4002 |
| Penal Income | INCOME | 4003 | 4003 | 4003 |
| Provision Expense | EXPENSE | 5001 | 5010 | 5010 |
| Provision NPA | ASSET | 1003 | 2010 | 2020 |
| Write-Off Expense | EXPENSE | 5002 | 5002 | 5002 |
| Interest Suspense | LIABILITY | 2100 | 2100 | 2100 |

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
GL Mapping:       2010 / 2010 / 1100 / 4010 / 4002 / 4003 / 5010 / 2010 / 5002 / 2100
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
GL Mapping:       2020 / 2020 / 1100 / 4010 / 4002 / 4003 / 5010 / 2020 / 5002 / 2100
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
| GL Type Match | Each GL code must match expected account type (ASSET/INCOME/EXPENSE/LIABILITY) |
| Retired Block | RETIRED products cannot be edited |

---

## 7. Important Notes

1. **GL Cache:** When GL codes are changed on a product, the system automatically evicts
   the ProductGLResolver cache. All future transactions will use the new GL codes.

2. **Active Accounts Warning:** The edit form shows a warning when the product has active
   loan or deposit accounts. GL code changes affect all future transactions on these accounts.

3. **CASA Field Reinterpretation:** For CASA products, `Min Amount` = minimum balance and
   `Max Amount` = maximum balance (0 = unlimited). Tenure fields should be 0.

4. **Immutable Fields:** Product Code, Category, and Currency cannot be changed after creation.
   To change these, create a new product and retire the old one.

5. **Floating Rate per RBI:** Since October 2019, all new floating rate retail loans must be
   linked to an external benchmark (EBLR). Prepayment penalty is prohibited on floating rate loans.
