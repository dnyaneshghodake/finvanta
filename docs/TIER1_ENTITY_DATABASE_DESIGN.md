# Tier-1 Grade CBS: Entity & Database Design Patterns

## Table of Contents
1. [Entity Design Principles](#entity-design-principles)
2. [Core Entities](#core-entities)
3. [Entity Relationships](#entity-relationships)
4. [Database Indexing Strategy](#database-indexing-strategy)
5. [Audit & Compliance Entities](#audit--compliance-entities)
6. [Performance Optimization](#performance-optimization)
7. [Data Retention Policy](#data-retention-policy)

---

## Entity Design Principles

### 1. Normalization

**Third Normal Form (3NF) Applied**:
- No partial dependencies
- No transitive dependencies
- Reduces data redundancy
- Ensures data integrity

### 2. Single Responsibility

Each entity represents a single business concept:
- `Customer` - Customer information
- `Account` - Bank account
- `Transaction` - Fund transfer
- NOT: Customer + Account combined

### 3. Immutability Where Possible

Create entities are immutable after creation:
```java
@Entity
public class Transaction {
    // Once created, transaction should not be modified
    private final Long transactionId;
    private final BigDecimal amount;
    private final LocalDateTime createdDate;
    
    // Modified dates only when status changes
    private TransactionStatus status;  // Mutable
}
```

### 4. Soft Delete Strategy

Never physically delete records from banking systems:
```java
@Entity
public class Customer {
    @Column(name = "active", nullable = false)
    private Boolean active = true;
    
    @Column(name = "deleted_date")
    private LocalDateTime deletedDate;
    
    @Column(name = "deleted_by")
    private String deletedBy;
    
    @PreUpdate
    public void softDelete() {
        if (!this.active) {
            this.deletedDate = LocalDateTime.now();
        }
    }
}

// Usage in repository
// Instead of: repository.delete(customer);
// Do this:
customer.setActive(false);
repository.save(customer);
```

### 5. Temporal Data

Track all data changes for audit:
```java
@Entity
@EntityListeners(AuditingEntityListener.class)
public class Account {
    @CreatedDate
    private LocalDateTime createdDate;
    
    @CreatedBy
    private String createdBy;
    
    @LastModifiedDate
    private LocalDateTime lastModifiedDate;
    
    @LastModifiedBy
    private String lastModifiedBy;
    
    @Version
    private Long version;  // Optimistic locking
}
```

---

## Core Entities

### Customer Entity

```
TABLE: t_customers

Attributes:
├── id (PK)
├── customer_id (UNIQUE)           [CUST + timestamp]
├── name (NOT NULL)                [Max 100 chars]
├── email (UNIQUE, NOT NULL)       [Validated]
├── phone_number                   [E.164 format]
├── date_of_birth                  [For age validation]
├── gender                         [M/F/Other]
├── kyc_status                     [PENDING/VERIFIED/REJECTED]
├── customer_category              [Individual/Corporate/NRI]
├── risk_score                     [0-100]
├── blacklisted                    [true/false]
├── pep_status                     [Politically Exposed Person check]
├── status                         [ACTIVE/INACTIVE/SUSPENDED]
├── created_date
├── created_by
├── last_modified_date
├── last_modified_by
├── deleted                        [Soft delete flag]
└── version                        [Optimistic locking]

Indexes:
├── idx_email (UNIQUE)
├── idx_customer_id (UNIQUE)
├── idx_status
├── idx_created_date
└── idx_blacklisted
```

### Account Entity

```
TABLE: t_accounts

Attributes:
├── id (PK)
├── account_number (UNIQUE)        [Bank provided]
├── iban (UNIQUE)                  [International standard]
├── customer_id (FK)               [Relationship to Customer]
├── account_type                   [SAVINGS/CURRENT/SALARY]
├── currency                       [ISO 4217 code]
├── status                         [ACTIVE/INACTIVE/CLOSED/DORMANT]
├── opening_date                   [Account opened date]
├── closing_date                   [If closed]
├── current_balance                [Calculated field]
├── ledger_balance                 [Cleared amount]
├── available_balance              [For overdraft calc]
├── minimum_balance                [Required minimum]
├── daily_transfer_limit           [Max per day]
├── monthly_transfer_limit         [Max per month]
├── overdraft_limit                [Max allowed]
├── overdraft_rate                 [Interest on OD]
├── interest_rate_savings          [Rate for savings]
├── auto_renewal_enabled           [For FDs/RDs]
├── dormancy_days                  [Days before dormant]
├── dormancy_flag                  [false/true]
├── sanctions_screened             [AMLCFT checked]
├── gl_account_mapping             [GL Account mapping]
├── branch_id                      [Opening branch]
├── created_date
├── created_by
├── last_modified_date
├── last_modified_by
├── deleted                        [Soft delete]
└── version                        [Optimistic locking]

Indexes:
├── idx_account_number (UNIQUE)
├── idx_iban (UNIQUE)
├── idx_customer_id (FK)
├── idx_status
├── idx_account_type
├── idx_currency
└── idx_dormancy_flag
```

### Transaction Entity

```
TABLE: t_transactions

Attributes:
├── id (PK)
├── transaction_id (UNIQUE)        [UTR/Reference]
├── from_account_id (FK)           [Debited account]
├── to_account_id (FK)             [Credited account]
├── transaction_type               [TRANSFER/DEPOSIT/WITHDRAWAL/LOAN_EMI]
├── transaction_status             [PENDING/SUCCESS/FAILED/REVERSED]
├── amount                         [BigDecimal, not double]
├── currency                       [ISO code]
├── debit_currency_rate            [For multi-currency]
├── credit_currency_rate           [For multi-currency]
├── converted_amount               [If multi-currency]
├── charges_deducted               [Service charges]
├── tax_deducted                   [TDS if applicable]
├── net_amount                     [After charges]
├── narration                      [Transaction description]
├── channel                        [MOBILE/WEB/ATM/BRANCH]
├── initiated_by                   [User ID / System]
├── initiated_date                 [When initiated]
├── approved_by                    [If needed]
├── approved_date                  [Approval timestamp]
├── posted_date                    [GL posting date]
├── cleared_date                   [Payment cleared]
├── reversal_reason                [If reversed]
├── parent_transaction_id          [For reversals]
├── gl_entry_reference             [Link to GL]
├── cheque_number                  [If cheque]
├── cheque_status                  [PENDING/CLEARED/BOUNCED]
├── created_date
├── last_modified_date
└── version                        [Optimistic locking]

Indexes:
├── idx_transaction_id (UNIQUE)
├── idx_from_account
├── idx_to_account
├── idx_status
├── idx_transaction_type
├── idx_created_date
├── idx_posted_date
└── COMPOSITE: (from_account, created_date)
```

### General Ledger Entity

```
TABLE: t_general_ledger

Attributes:
├── id (PK)
├── gl_code (UNIQUE)               [e.g., 1010, 1020]
├── gl_name                        [Account head name]
├── gl_type                        [ASSET/LIABILITY/EQUITY/INCOME/EXPENSE]
├── gl_nature                      [DEBIT/CREDIT]
├── gl_level                       [1ST/2ND/3RD]
├── parent_gl_id                   [Hierarchical]
├── opening_balance                [Year start]
├── current_balance                [Calculated]
├── posting_enabled                [Can post to this?]
├── cost_center_id                 [For cost allocation]
├── profit_center_id               [For profit calc]
├── reconciliation_frequency       [DAILY/MONTHLY]
├── created_date
├── last_modified_date
└── version

Indexes:
├── idx_gl_code (UNIQUE)
├── idx_gl_type
├── idx_parent_gl
└── idx_cost_center
```

### GL Entry (Journal) Entity

```
TABLE: t_gl_entries (Posting Journal)

Attributes:
├── id (PK)
├── entry_id (UNIQUE)              [Journal entry ID]
├── batch_id                       [For batch processing]
├── posting_date                   [Date of posting]
├── value_date                     [For settlement]
├── gl_account_debit               [Debit GL account]
├── gl_account_credit              [Credit GL account]
├── amount_debit                   [Debit amount]
├── amount_credit                  [Credit amount]
├── narration                      [Description]
├── reference_id                   [Transaction/Upload ref]
├── reference_type                 [TRANSACTION/ADJUSTMENT]
├── posted_by                      [User/System]
├── approval_status                [PENDING/APPROVED]
├── approved_by                    [Approver user]
├── posted_date                    [When posted]
├── cost_center                    [For allocation]
├── profit_center                  [For tracking]
├── customer_id                    [For tracing]
├── branch_id                      [Branch info]
├── module                         [DEPOSITS/LOANS/etc]
├── created_date
├── last_modified_date
└── version

Indexes:
├── idx_entry_id (UNIQUE)
├── idx_posting_date
├── idx_gl_account_debit
├── idx_gl_account_credit
├── idx_reference_id
├── idx_batch_id
└── COMPOSITE: (posting_date, gl_account_debit)
```

### Loan Entity

```
TABLE: t_loans

Attributes:
├── id (PK)
├── loan_id (UNIQUE)               [Loan reference number]
├── customer_id (FK)               [Customer]
├── loan_type                      [PERSONAL/HOME/AUTO/EDUCATION]
├── loan_amount_applied            [Requested]
├── loan_amount_approved           [Sanctioned]
├── loan_amount_disbursed          [Actual disbursed]
├── rate_of_interest               [p.a.]
├── loan_tenure_months             [Duration]
├── emi_amount                     [Monthly installment]
├── emi_count_total                [Total EMI count]
├── emi_count_paid                 [Paid EMIs]
├── emi_count_pending              [Remaining]
├── emi_paid_upto_date             [Last paid date]
├── first_emi_due_date             [First EMI date]
├── last_emi_due_date              [Final EMI date]
├── loan_status                    [PENDING/APPROVED/DISBURSED/ACTIVE/CLOSED]
├── approval_date
├── disbursement_date
├── closure_date
├── interest_type                  [SIMPLE/COMPOUND]
├── security_type                  [UNSECURED/SECURED]
├── security_value                 [If secured]
├── rate_applicable_as_on          [Rate reference date]
├── credit_score                   [CIBIL score at approval]
├── insurance_premium              [Optional]
├── processing_fee                 [One-time fee]
├── foreclosure_charges            [Early repayment charges]
├── penal_interest_rate            [On delayed payment]
├── gl_account_principal           [GL mapping]
├── gl_account_interest            [GL mapping]
├── branch_id                      [Originating branch]
├── created_date
├── created_by
└── version

Indexes:
├── idx_loan_id (UNIQUE)
├── idx_customer_id (FK)
├── idx_loan_status
├── idx_emi_paid_upto_date
└── idx_loan_type
```

### Fixed Deposit Entity

```
TABLE: t_fixed_deposits

Attributes:
├── id (PK)
├── fd_id (UNIQUE)                 [Certificate number]
├── customer_id (FK)               [Customer]
├── account_id (FK)                [Deposit source]
├── principal_amount               [Invested amount]
├── rate_of_interest               [% p.a.]
├── tenor_months                   [Duration]
├── fd_start_date                  [Effective date]
├── fd_maturity_date               [Due date]
├── interest_payout_frequency      [MONTHLY/QUARTERLY/ANNUAL/MATURITY]
├── interest_compounding           [MONTHLY/QUARTERLY/ANNUAL]
├── total_interest                 [Calculated]
├── maturity_amount                [Principal + Interest]
├── tax_deducted                   [TDS applicable]
├── net_payout_amount              [After tax]
├── payout_account_id              [Where to credit]
├── fd_status                      [ACTIVE/MATURED/CLOSED/PREMATURE_CLOSED]
├── auto_renewal_enabled           [On maturity?]
├── auto_renewal_power             [New tenor if renewed]
├── payout_date                    [Actual payout]
├── created_date
├── created_by
├── closed_date
└── version

Indexes:
├── idx_fd_id (UNIQUE)
├── idx_customer_id
├── idx_fd_maturity_date
└── idx_fd_status
```

### Interest Accrual Entity

```
TABLE: t_interest_accruals

Attributes:
├── id (PK)
├── accrual_id (UNIQUE)            [Unique reference]
├── entity_type                    [ACCOUNT/LOAN/DEPOSIT]
├── entity_id                      [Account/Loan/FD ID]
├── entity_reference               [Account/Loan number]
├── principal_amount               [Base for calculation]
├── rate_of_interest               [% p.a.]
├── calculation_date               [When calculated]
├── accrual_period_from
├── accrual_period_to
├── number_of_days                 [Days in period]
├── interest_calculated            [Amount]
├── tax_deducted                   [TDS amount]
├── net_interest                   [After tax]
├── status                         [ACCRUED/CREDITED/REVERSED]
├── credited_date                  [When credited to account]
├── gl_entry_reference             [Journal entry ID]
├── rate_applicable_as_on          [Rate reference date]
├── created_date
└── version

Indexes:
├── idx_accrual_id (UNIQUE)
├── idx_entity_id
├── idx_calculation_date
└── idx_status
```

---

## Entity Relationships

### Relationship Diagram (ER Diagram)

```
┌────────────────────┐
│    CUSTOMER        │
├────────────────────┤
│ id (PK)            │
│ customer_id        │
│ name               │
│ email              │
│ phone              │
│ status             │
└────────┬───────────┘
         │ 1 (One)
         │ Has Many
         │ 
    ┌────┴──────────────────────────┐
    │                               │
    │    M (Many)                   │
    │                               │
┌───▼──────────────────┐        ┌──▼─────────────────┐
│     ACCOUNT          │        │      LOAN          │
├──────────────────────┤        ├────────────────────┤
│ id (PK)              │        │ id (PK)            │
│ customer_id (FK)     │        │ customer_id (FK)   │
│ account_number       │        │ loan_id            │
│ account_type         │        │ loan_amount        │
│ status               │        │ emi_amount         │
└────┬────────┬────────┘        │ status             │
     │        │                 └────┬───────────────┘
     │        │ 1:M                  │ 1:M
     │        │                      │
 1:M │        └──────┬───────────────┘
     │               │
┌────▼───────────────┴────────────────────┐
│        TRANSACTION                      │
├─────────────────────────────────────────┤
│ id (PK)                                 │
│ from_account_id (FK)                    │
│ to_account_id (FK)                      │
│ transaction_type                        │
│ amount                                  │
│ status                                  │
└───────────────────────────────────────────┘
        │                  │
        │ references       │ posts to
        │ 1:M              │ 1:M
        │                  │
        └──────┬───────────┘
               │
        ┌──────▼──────────┐
        │  GL_ENTRY       │
        ├─────────────────┤
        │ id (PK)         │
        │ gl_debit (FK)   │
        │ gl_credit (FK)  │
        │ amount          │
        └─────────────────┘
               │ FK
               │ References
               │ M
               │
        ┌──────▼──────────────┐
        │ GENERAL_LEDGER      │
        ├─────────────────────┤
        │ id (PK)             │
        │ gl_code             │
        │ gl_name             │
        │ gl_type             │
        │ current_balance     │
        └─────────────────────┘
```

### One-to-Many Relationship Example

```java
@Entity
@Table(name = "t_customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String customerId;
    
    private String name;
    
    // One customer has many accounts
    @OneToMany(
        mappedBy = "customer",
        cascade = CascadeType.ALL,
        fetch = FetchType.LAZY,
        orphanRemoval = true
    )
    private Set<Account> accounts = new HashSet<>();
    
    // One customer has many loans
    @OneToMany(
        mappedBy = "customer",
        cascade = CascadeType.ALL,
        fetch = FetchType.LAZY
    )
    private Set<Loan> loans = new HashSet<>();
    
    // Helper methods for bidirectional relationship
    public void addAccount(Account account) {
        accounts.add(account);
        account.setCustomer(this);
    }
    
    public void removeAccount(Account account) {
        accounts.remove(account);
        account.setCustomer(null);
    }
}

@Entity
@Table(name = "t_accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String accountNumber;
    
    // Many accounts belong to one customer
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;
    
    // Transactions from/to this account
    @OneToMany(mappedBy = "fromAccount", fetch = FetchType.LAZY)
    private Set<Transaction> outgoingTransactions = new HashSet<>();
    
    @OneToMany(mappedBy = "toAccount", fetch = FetchType.LAZY)
    private Set<Transaction> incomingTransactions = new HashSet<>();
}

@Entity
@Table(name = "t_transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;
    
    private BigDecimal amount;
    private TransactionStatus status;
}
```

---

## Database Indexing Strategy

### Index Categories

```
Type 1: PRIMARY KEY Indexes
├── Automatic on all PKs
├── UNIQUE constraint included
└── Used for: PK lookups, JOIN operations

Type 2: UNIQUE Indexes
├── customer_id (UNIQUE)
├── account_number (UNIQUE)
├── email (UNIQUE)
├── iban (UNIQUE)
└── Used for: Preventing duplicates, alternate lookups

Type 3: Foreign Key Indexes
├── customer_id on account table
├── account_id on transaction table
├── customer_id on loan table
└── Used for: JOIN performance

Type 4: Search Indexes
├── status columns
├── date columns (created_date, posted_date)
├── transaction_type
└── Used for: WHERE clause filtering

Type 5: Composite Indexes
├── (from_account_id, created_date)
├── (posting_date, gl_account_debit)
├── (customer_id, status, created_date)
└── Used for: Complex WHERE conditions

Type 6: Full-Text Indexes
├── customer_name
├── transaction_narration
├── gl_name
└── Used for: Text search operations

Type 7: Covering Indexes
├── Include all columns needed for query
├── Allows index-only scan
└── Example: (account_id, status, balance)
```

### Indexing Examples

```sql
-- Primary Key (automatic on PK)
CREATE TABLE t_customers (
    id BIGINT PRIMARY KEY IDENTITY(1,1),
    customer_id VARCHAR(20) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL
);

-- Foreign Key Index (for JOIN performance)
CREATE INDEX idx_account_customer_id 
ON t_accounts(customer_id);

-- Composite Index for common queries
CREATE INDEX idx_account_status_date 
ON t_accounts(status, created_date DESC);

-- Covering Index (includes all needed columns)
CREATE INDEX idx_account_balance 
ON t_accounts(account_number, status, current_balance)
INCLUDE (customer_id, last_modified_date);

-- Filtered Index (only active accounts)
CREATE INDEX idx_active_accounts 
ON t_accounts(status, created_date)
WHERE status = 'ACTIVE';

-- Full-Text Index
CREATE FULLTEXT INDEX idx_customer_name 
ON t_customers(name);

-- Partitioned Index by range
CREATE INDEX idx_transaction_by_date 
ON t_transactions(created_date)
PARTITION BY RANGE (YEAR(created_date));
```

### Index Maintenance

```sql
-- Statistics update
UPDATE STATISTICS t_customers;
UPDATE STATISTICS idx_customer_email;

-- Fragmentation check
SELECT 
    object_name,
    index_name,
    avg_fragmentation_in_percent
FROM sys.dm_db_index_physical_stats(DEFAULT, DEFAULT, DEFAULT, DEFAULT, 'LIMITED')
WHERE avg_fragmentation_in_percent > 10;

-- Index rebuild (heavy fragmentation > 30%)
ALTER INDEX idx_account_status_date ON t_accounts REBUILD;

-- Index reorganize (light fragmentation 10-30%)
ALTER INDEX idx_account_status_date ON t_accounts REORGANIZE;

-- Disable unused index
ALTER INDEX idx_unused_index ON t_table DISABLE;

-- Drop unused index
DROP INDEX idx_unused_index ON t_table;
```

---

## Audit & Compliance Entities

### Audit Log Entity

```
TABLE: t_audit_logs

Attributes:
├── id (PK)
├── audit_id (UNIQUE)              [Unique audit reference]
├── entity_type                    [CUSTOMER/ACCOUNT/TRANSACTION]
├── entity_id                      [Customer/Account/Txn ID]
├── operation                      [CREATE/READ/UPDATE/DELETE]
├── old_value                      [Before change (JSON)]
├── new_value                      [After change (JSON)]
├── changed_by                     [User who made change]
├── changed_date                   [When changed]
├── change_reason                  [Why changed]
├── ip_address                     [Source IP]
├── user_agent                     [Browser/App info]
├── session_id                     [Session reference]
├── status                         [SUCCESS/FAILURE]
├── error_message                  [If failed]
└── created_date

Indexes:
├── idx_audit_id (UNIQUE)
├── idx_entity_id
├── idx_changed_date
├── idx_changed_by
└── COMPOSITE: (entity_type, entity_id, changed_date)
```

### Transaction Monitoring Entity

```
TABLE: t_transaction_monitor

Attributes:
├── id (PK)
├── transaction_id (FK)            [Reference to transaction]
├── amount                         [Transaction amount]
├── threshold_breached             [Limit exceeded?]
├── risk_score                     [0-100]
├── aml_status                     [CLEAR/SUSPICIOUS/BLOCKED]
├── sanctions_check                [DONE/PENDING]
├── sanctions_result               [MATCH/NO_MATCH]
├── flagged_reason                 [Why flagged]
├── flagged_date
├── reviewed_by                    [Reviewer user]
├── reviewed_date
├── action_taken                   [APPROVED/REJECTED/PENDING]
├── created_date
└── version

Indexes:
├── idx_transaction_id (FK)
├── idx_aml_status
├── idx_flagged_date
└── idx_risk_score
```

---

## Performance Optimization

### Query Optimization Practices

```
1. Use Indexes Wisely:
   ✓ Index columns used in WHERE, JOIN, ORDER BY
   ✗ Don't index too many columns (write performance)
   ✓ Use composite indexes for complex queries

2. Avoid N+1 Query Problem:
   ✓ Use JOIN fetch in queries
   ✓ Use @Query with JOIN FETCH
   ✓ Use EntityGraph
   ✗ Don't load collections lazily in loops

3. Pagination Always:
   ✓ Use Pageable for list queries
   ✓ SELECT TOP N instead of SELECT *
   ✗ Don't load entire table into memory

4. Projection:
   ✓ Select only needed columns
   ✓ Use DTO projection
   ✗ Don't select * if only 2 columns needed

5. Connection Pooling:
   ✓ HikariCP with 10-20 connections
   ✓ Proper timeout configuration
   ✗ Don't connect without pooling
```

### Caching Strategy

```
L1 Cache (Hibernate Session):
├── Scope: Per transaction
├── Automatic
├── Used for: Object identity management
├── TTL: Transaction lifetime

L2 Cache (Redis):
├── Scope: Application-wide
├── Scope: 1-24 hours
├── Used for: Frequently accessed data
├── Example: Rate masters, GL heads

Query Cache:
├── Query results cached
├── Invalidated on entity change
├── Used for: Expensive queries, read-only
├── Example: Chart of accounts
```

### SQL Optimization Tips

```sql
-- GOOD: Use indexes effectively
SELECT * FROM t_accounts 
WHERE status = 'ACTIVE' AND created_date > '2024-01-01'
ORDER BY created_date DESC;
-- Indexes: (status, created_date DESC)

-- BAD: Full table scan
SELECT * FROM t_accounts 
WHERE YEAR(created_date) = 2024;
-- Function on column disables index

-- GOOD: Batch operations
INSERT INTO t_transactions (account_id, amount, date)
SELECT account_id, amount, date FROM staging_table
WHERE status = 'PENDING';

-- BAD: Row-by-row
FOR EACH ROW:
    INSERT INTO t_transactions...

-- GOOD: Join efficiently
SELECT a.*, c.name FROM t_accounts a
INNER JOIN t_customers c ON a.customer_id = c.id
WHERE a.status = 'ACTIVE'
AND a.created_date > '2024-01-01';

-- BAD: SELECT * without filter
SELECT * FROM t_accounts a, t_customers c
WHERE a.customer_id = c.id;
```

---

## Data Retention Policy

### Retention Schedule

```
Entity Type              Retention Period    Archive
────────────────────────────────────────────────────────
Customer (Active)        Lifetime            No
Customer (Inactive)      7 years             Yes (Year 3+)
Account (Active)         Lifetime            No
Account (Closed)         7 years             Yes (Year 3+)
Transaction              7 years             Yes (Year 2+)
GL Entry                 10 years            Yes (Year 5+)
Audit Logs               5 years             Yes (Year 3+)
Interest Accrual         5 years             Yes (Year 3+)
Login/Session Logs       1 year              No (Purge)
Mobile App Logs          30 days             No (Purge)
Error Logs               90 days             No (Purge)
```

### Archive Strategy

```
Year 1:  LIVE Database
         - Current transactions
         - Active customers/accounts
         - Recent history

Year 2+: Archive Database (SSD)
         - 2-year-old data
         - Compressed storage
         - Read-only access
         - Compliance queries

Year 7+: Cold Storage (Tape/Cloud)
         - Old historical data
         - Required for audit
         - Long-term retention
         - Minimal access

Purge:   Delete after retention
         - App logs after 30-90 days
         - Session logs after 1 year
         - With audit trail of deletion
```

### Data Archive Process

```sql
-- Archive old transactions (2+ years)
INSERT INTO archive_db.t_transactions
SELECT * FROM operational_db.t_transactions
WHERE DATEDIFF(YEAR, created_date, GETDATE()) >= 2;

-- Delete after archiving
DELETE FROM operational_db.t_transactions
WHERE DATEDIFF(YEAR, created_date, GETDATE()) >= 2;

-- Compress archived data
COMPRESS DATA IN archive_db.t_transactions;

-- Create view for transparency
CREATE VIEW t_transactions_all AS
SELECT * FROM operational_db.t_transactions
UNION ALL
SELECT * FROM archive_db.t_transactions;
```

---

## Consistency & Integrity

### DATA INTEGRITY CONSTRAINTS

```sql
-- NOT NULL constraints
ALTER TABLE t_accounts 
ADD CONSTRAINT chk_account_number 
CHECK (account_number IS NOT NULL AND LEN(account_number) >= 10);

-- CHECK constraints
ALTER TABLE t_transactions
ADD CONSTRAINT chk_positive_amount
CHECK (amount > 0);

ALTER TABLE t_accounts
ADD CONSTRAINT chk_valid_balance
CHECK (current_balance >= 0 OR overdraft_limit > 0);

-- UNIQUE constraints
ALTER TABLE t_customers
ADD CONSTRAINT uk_email UNIQUE (email);

-- Foreign Key with CASCADE
ALTER TABLE t_accounts
ADD CONSTRAINT fk_customer_account
FOREIGN KEY (customer_id) REFERENCES t_customers(id)
ON DELETE RESTRICT;  -- Prevent customer deletion if accounts exist

-- Default values
ALTER TABLE t_customers
ADD CONSTRAINT df_active
DEFAULT 1 FOR active;
```

---

This comprehensive entity and database design documentation provides the foundation for building a robust, high-performance Tier-1 CBS system.

