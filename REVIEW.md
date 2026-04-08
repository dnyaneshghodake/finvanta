# Finvanta CBS — Code Review & Formatting Standards

## Coding Standards

This project uses **Spotless Maven Plugin** with **Palantir Java Format** for
automated code formatting enforcement.

### Quick Commands

```bash
# Check formatting (CI gate — fails on violations)
mvn spotless:check

# Auto-fix formatting violations locally
mvn spotless:apply
```

### Java Formatting Rules

- **Formatter**: Palantir Java Format 2.47.0 (4-space indent)
- **Import order**: `com.finvanta` → `com` → `jakarta` → `javax` → `java` → `org` → static imports
- **Unused imports**: Automatically removed
- **Trailing whitespace**: Automatically trimmed
- **File endings**: Must end with newline

### Incremental Adoption

Spotless is configured with `ratchetFrom=origin/master` — only files changed
since master are checked. This means:

- Existing files are NOT reformatted until they are modified
- All new files must follow the formatting standard
- All modified files are auto-formatted on `mvn spotless:apply`

### Before Committing

Always run before pushing:

```bash
mvn spotless:apply
```

### Financial Code Review Rules

Per RBI IT Governance Direction 2023:

- **GL postings**: Every financial transaction must have balanced DR/CR entries
- **Concurrency**: All balance mutations must use `PESSIMISTIC_WRITE` locks
- **Idempotency**: Client-facing financial operations must support idempotency keys
- **Audit trail**: Every state change must be logged via `AuditService`
- **Branch isolation**: MAKER/CHECKER see only their branch data; ADMIN sees all
- **XSS prevention**: All user-supplied data in JSP must use `<c:out>` — never raw `${}`
