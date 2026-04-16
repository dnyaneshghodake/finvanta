# Finvanta CBS — Deployment & Operations Guide

**Version:** 1.0 | **Classification:** INTERNAL — Operations Team Only

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [SQL Server Database Setup](#2-sql-server-database-setup)
3. [Build the Project](#3-build-the-project)
4. [Profile Configuration](#4-profile-configuration)
5. [DB Credential Encryption](#5-credential-encryption)
6. [MFA Encryption Key Setup](#6-mfa-encryption-key-setup)
7. [Running in IntelliJ IDEA](#7-running-in-intellij-idea)
8. [Running on Tomcat 10 Server](#8-running-on-tomcat-10-server)
9. [Running with Docker](#9-running-with-docker)
10. [Verifying the Deployment](#10-verifying-the-deployment)
11. [Troubleshooting](#11-troubleshooting)
12. [Security Checklist](#12-security-checklist)

---

## 1. Prerequisites

| Software | Version | Purpose |
|----------|---------|---------|
| Java JDK | 17+ | Runtime |
| Apache Maven | 3.8+ | Build tool |
| SQL Server | 2019+ | Database |
| SSMS | Latest | DB management |
| IntelliJ IDEA | 2023+ | IDE (optional) |
| Apache Tomcat | 10.1.x | App server (production) |

Verify installations:

```cmd
java -version
mvn -version
```

---

## 2. SQL Server Database Setup

### 2.1 Create Database

Open SSMS, connect to your SQL Server, run:

```sql
CREATE DATABASE finvanta;
GO
```

### 2.2 Configure sa Account

> **⚠️ SECURITY:** Replace the placeholder password below with a strong password
> (min 16 chars, mixed case, digits, special chars). Never use the example
> password in any environment. Per RBI Cyber Security Framework 2024 §4.2:
> credentials must never appear in documentation or source control.

```sql
ALTER LOGIN sa ENABLE;
GO
ALTER LOGIN sa WITH PASSWORD = '<YOUR-STRONG-PASSWORD>';
GO
```

### 2.3 Enable SQL Server Authentication

1. SSMS → right-click server → **Properties** → **Security**
2. Select **SQL Server and Windows Authentication mode**
3. Click **OK**

### 2.4 Enable TCP/IP on Port 1433

1. Open **SQL Server Configuration Manager**
2. **SQL Server Network Configuration** → **Protocols for MSSQLSERVER**
3. Enable **TCP/IP**
4. TCP/IP Properties → **IP Addresses** → **IPAll** → TCP Port = `1433`

### 2.5 Restart SQL Server

Windows Services (`services.msc`) → **SQL Server** → **Restart**

### 2.6 Test Connection

SSMS → Connect to `localhost,1433` → SQL Server Auth → `sa` / `<your-password>`

---

## 3. Build the Project

```cmd
cd D:\CBS\finvanta
mvn clean package -DskipTests
```

Output: `target\finvanta-0.0.1-SNAPSHOT.war`

---

## 4. Profile Configuration

| Profile | Database | Activate With |
|---------|----------|---------------|
| `dev` | H2 in-memory | `spring.profiles.active=dev` |
| `sqlserver` | SQL Server localhost | `spring.profiles.active=sqlserver` |
| `prod` | SQL Server via env vars | `spring.profiles.active=prod` |

### Switch Profile

Edit `src\main\resources\application.properties`:

```properties
spring.profiles.active=sqlserver
```

---

## 5. Credential Encryption

### 5.1 Overview

Database credentials are encrypted with AES-256-GCM. The encryption key is stored as an environment variable, never in source code.

### 5.2 Generate Encryption Key

**PowerShell (Windows):**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=genkey"
```

**Command Prompt (cmd):**
```cmd
mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="genkey"
```

**Important:** PowerShell requires quotes around each `-D` argument. Command Prompt does not.

Output:

```
Generated key: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1

Set the key as environment variable before starting the application:
  PowerShell:      $env:FINVANTA_DB_ENCRYPTION_KEY = "a1b2c3d4..."
  Command Prompt:  set FINVANTA_DB_ENCRYPTION_KEY=a1b2c3d4...
  Linux/Mac:       export FINVANTA_DB_ENCRYPTION_KEY=a1b2c3d4...
  IntelliJ:        Run > Edit Configurations > Environment Variables > Add FINVANTA_DB_ENCRYPTION_KEY
```

Save the 64-character hex key. Use the SAME key for all subsequent steps.

**To set the key immediately (choose one based on your terminal):**

**⚠️ PowerShell and Command Prompt use DIFFERENT syntax:**

PowerShell (uses `$env:`):
```powershell
$env:FINVANTA_DB_ENCRYPTION_KEY = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1"
```

Command Prompt (uses `set`):
```cmd
set FINVANTA_DB_ENCRYPTION_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1
```

**Common mistake:** Using `set` in PowerShell does NOT work. Using `export` in Windows does NOT work. Use the correct syntax for your terminal.

### 5.3 Encrypt Username

**⚠️ IMPORTANT:** Replace the key below with YOUR actual 64-character key from Step 5.2. Do NOT type the literal text `YOUR_KEY`.

**Example:** If Step 5.2 gave you `1a768f83c273c4276ef5310afc1f6e3e5844e0755bbaf7f22a94fb2a52e37214`, then use that exact value.

**PowerShell:**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=encrypt 1a768f83c273c4276ef5310afc1f6e3e5844e0755bbaf7f22a94fb2a52e37214 sa"
```

**Command Prompt:**
```cmd
mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="encrypt 1a768f83c273c4276ef5310afc1f6e3e5844e0755bbaf7f22a94fb2a52e37214 sa"
```

Output:

```
Encrypted: ENC(base64-ciphertext-here)
Paste this into your properties file.
```

Copy the entire `ENC(...)` value including the wrapper.

### 5.4 Encrypt Password

Use the SAME key from Step 5.2:

**PowerShell:**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=encrypt 1a768f83c273c4276ef5310afc1f6e3e5844e0755bbaf7f22a94fb2a52e37214 sqlserver#123"
```

**Command Prompt:**
```cmd
mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="encrypt 1a768f83c273c4276ef5310afc1f6e3e5844e0755bbaf7f22a94fb2a52e37214 sqlserver#123"
```

Copy the entire `ENC(...)` output.

### 5.5 Update Properties

Edit `application-sqlserver.properties`, replace:

```properties
spring.datasource.username=ENC(your-encrypted-username)
spring.datasource.password=ENC(your-encrypted-password)
```

### 5.6 Rebuild

```cmd
mvn clean package -DskipTests
```

### 5.7 Verify Decryption (Optional)

To confirm an encrypted value decrypts correctly:

**PowerShell:**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=decrypt YOUR_KEY base64-ciphertext-without-ENC-wrapper"
```

**Command Prompt:**
```cmd
mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="decrypt YOUR_KEY base64-ciphertext-without-ENC-wrapper"
```

Note: For decrypt, paste only the Base64 part — without `ENC(` and `)`.

### 5.8 Quick Reference

**PowerShell commands (quotes around each -D):**

| Action | Command |
|--------|---------|
| Generate key | `mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=genkey"` |
| Encrypt | `mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=encrypt KEY VALUE"` |
| Decrypt | `mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=decrypt KEY CIPHERTEXT"` |

**Command Prompt commands:**

| Action | Command |
|--------|---------|
| Generate key | `mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="genkey"` |
| Encrypt | `mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="encrypt KEY VALUE"` |
| Decrypt | `mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="decrypt KEY CIPHERTEXT"` |

---

## 6. MFA Encryption Key Setup

### 6.1 Overview

MFA (TOTP) secrets are encrypted at rest using AES-256-GCM per RBI IT Governance §8.4.
The encryption key **MUST** be set via environment variable for sqlserver and prod profiles.
The application **WILL NOT START** without it on persistent database profiles.

### 6.2 Generate MFA Key

**PowerShell:**
```powershell
openssl rand -hex 32
```

**If openssl is not available, use the CBS key generator:**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=genkey"
```

Output: `a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1`

**⚠️ This key is DIFFERENT from the DB encryption key.** Use a separate key for MFA.

### 6.3 Set MFA Key

**PowerShell:**
```powershell
$env:MFA_ENCRYPTION_KEY = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1"
```

**Command Prompt:**
```cmd
set MFA_ENCRYPTION_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1
```

**Linux/Mac:**
```bash
export MFA_ENCRYPTION_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1
```

### 6.4 Verify

Start the application. You should see in the logs:

```
CBS SECURITY: MFA encryption key configured (non-default).
```

If you see this error, the key is not set:

```
FATAL: MFA encryption key must be overridden on persistent database profiles
```

### 6.5 Key Management Rules

| Rule | Details |
|------|---------|
| **Never commit to source** | Key must come from env var or secrets manager |
| **Different per environment** | Dev, UAT, Prod must each have unique keys |
| **Different from DB key** | MFA key ≠ FINVANTA_DB_ENCRYPTION_KEY |
| **Rotate annually** | Generate new key, re-encrypt all MFA secrets |
| **Backup securely** | Loss = all MFA secrets unrecoverable |
| **64 hex chars exactly** | 32 bytes = 256-bit AES key |

---

## 7. Running in IntelliJ IDEA

### 7.1 Without Encryption (Quick Start)

1. Set `spring.profiles.active=sqlserver` in `application.properties`
2. Open `FinvantaApplication.java` → click Run
3. Open `http://localhost:8080`

### 7.2 With Encrypted Credentials

1. Complete Section 5 and Section 6 first
2. **Run** → **Edit Configurations** → select `FinvantaApplication`
3. **Environment variables** → click `...` → Add:
   - Name: `FINVANTA_DB_ENCRYPTION_KEY` — Value: your DB key
   - Name: `MFA_ENCRYPTION_KEY` — Value: your MFA key
4. Click **OK** → **OK**
5. Run the application
6. Open `http://localhost:8080`

---

## 8. Running on Tomcat 10 Server

### 8.1 Deploy WAR

```cmd
mvn clean package -DskipTests
copy target\finvanta-0.0.1-SNAPSHOT.war C:\Tomcat10\webapps\ROOT.war
```

### 8.2 Configure Environment

#### Windows — Create `C:\Tomcat10\bin\setenv.bat`:

```bat
@echo off
REM ============================================================
REM Finvanta CBS — Tomcat 10 JVM Configuration (Windows)
REM Per Finacle/Temenos Tier-1 CBS JVM sizing standards.
REM ============================================================

REM --- Spring profile ---
set "SPRING_PROFILES_ACTIVE=sqlserver"

REM --- Encryption keys (from secrets manager / vault in production) ---
set "FINVANTA_DB_ENCRYPTION_KEY=your-64-char-db-key"
set "MFA_ENCRYPTION_KEY=your-64-char-mfa-key"

REM --- JVM Heap: 2GB min / 4GB max for CBS workloads ---
REM CBS sizing: EOD parallel threads, Caffeine caches, Hibernate sessions,
REM multi-tenant ledger operations. 512MB-1GB is inadequate for production.
set "JAVA_OPTS=%JAVA_OPTS% -Xms2g -Xmx4g"

REM --- GC: G1GC for low-latency CBS transaction processing ---
set "JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

REM --- Metaspace: Spring Boot + Hibernate + JPA proxies need headroom ---
set "JAVA_OPTS=%JAVA_OPTS% -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m"

REM --- Encoding: INR currency symbols, Hindi/regional language support ---
set "JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8"

REM --- Timezone: IST for RBI business date calculations ---
set "JAVA_OPTS=%JAVA_OPTS% -Duser.timezone=Asia/Kolkata"

REM --- OOM handling: dump heap and exit cleanly on OutOfMemory ---
REM Per RBI IT Governance 2023 §7.3: CBS must not silently corrupt data on OOM.
set "JAVA_OPTS=%JAVA_OPTS% -XX:+HeapDumpOnOutOfMemoryError"
set "JAVA_OPTS=%JAVA_OPTS% -XX:HeapDumpPath=C:\Tomcat10\logs\heapdump.hprof"
set "JAVA_OPTS=%JAVA_OPTS% -XX:+ExitOnOutOfMemoryError"
```

#### Linux/Unix — Create `/opt/tomcat10/bin/setenv.sh`:

```bash
#!/bin/bash
# ============================================================
# Finvanta CBS — Tomcat 10 JVM Configuration (Linux)
# Per Finacle/Temenos Tier-1 CBS JVM sizing standards.
# Per RBI IT Governance Direction 2023 §7.1: production on Linux.
# ============================================================

export SPRING_PROFILES_ACTIVE="sqlserver"

# Encryption keys — source from vault/secrets manager in production
export FINVANTA_DB_ENCRYPTION_KEY="your-64-char-db-key"
export MFA_ENCRYPTION_KEY="your-64-char-mfa-key"

# JVM Heap: 2GB min / 4GB max
JAVA_OPTS="$JAVA_OPTS -Xms2g -Xmx4g"

# G1GC for low-latency CBS transaction processing
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Metaspace
JAVA_OPTS="$JAVA_OPTS -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m"

# Encoding + Timezone
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Kolkata"

# OOM handling
JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError"
JAVA_OPTS="$JAVA_OPTS -XX:HeapDumpPath=/opt/tomcat10/logs/heapdump.hprof"
JAVA_OPTS="$JAVA_OPTS -XX:+ExitOnOutOfMemoryError"

export JAVA_OPTS
```

After creating `setenv.sh`, make it executable: `chmod +x /opt/tomcat10/bin/setenv.sh`

### 8.3 Start / Stop

**Windows:**
```cmd
C:\Tomcat10\bin\startup.bat
C:\Tomcat10\bin\shutdown.bat
```

**Linux:**
```bash
/opt/tomcat10/bin/startup.sh
/opt/tomcat10/bin/shutdown.sh
```

### 8.4 View Logs

**Windows** (Tomcat uses date-stamped logs on Windows):
```cmd
type C:\Tomcat10\logs\catalina.%date:~-4%-%date:~4,2%-%date:~7,2%.log
```

**Linux:**
```bash
tail -f /opt/tomcat10/logs/catalina.out
```

### 8.5 Access

Open `http://localhost:8080`

---

## 9. Running with Docker

### 9.1 Build Image

```cmd
docker build -t finvanta-cbs:latest .
```

### 9.2 Run Container

```cmd
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=sqlserver \
  -e FINVANTA_DB_ENCRYPTION_KEY=your-db-key \
  -e MFA_ENCRYPTION_KEY=your-mfa-key \
  finvanta-cbs:latest
```

---

## 10. Verifying the Deployment

| Check | URL / Command | Expected |
|-------|---------------|----------|
| Health | `http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| Login | `http://localhost:8080` → admin / (default dev password) | Dashboard |
| DB Tables | SSMS: `SELECT * FROM app_users` | 6 users |
| Calendar | SSMS: `SELECT * FROM business_calendar WHERE business_date='2026-04-01'` | 3 rows (DAY_OPEN) |

---

## 11. Troubleshooting

| Error | Fix |
|-------|-----|
| `Login failed for user 'sa'` | Enable sa, set password (Section 2.2) |
| `Connection refused port 1433` | Enable TCP/IP (Section 2.4), restart SQL Server |
| `Cannot open database "finvanta"` | Create database (Section 2.1) |
| `CBS property decryption failed` | Check FINVANTA_DB_ENCRYPTION_KEY matches |
| `ENC(...) found but key not set` | Set FINVANTA_DB_ENCRYPTION_KEY env var |
| `FATAL: MFA encryption key must be overridden` | Set MFA_ENCRYPTION_KEY env var (Section 6) |
| `MFA encryption key contains invalid hex` | Key must be exactly 64 hex chars (0-9, a-f) |
| `Tables not created` | Verify profile is `sqlserver` not `dev` |
| `Port 8080 in use` | Stop other app or change port |

---

## 12. Security Checklist

### Development (sqlserver profile)
- [ ] SQL Server sa has strong password
- [ ] DB credentials encrypted with ENC(...)
- [ ] FINVANTA_DB_ENCRYPTION_KEY set as env var
- [ ] MFA_ENCRYPTION_KEY set as env var (Section 6)
- [ ] H2 console disabled (`spring.h2.console.enabled=false`)
- [ ] SQL logging routed to file only (not console)

### Production
- [ ] Dedicated DB user (not sa) with least privileges
- [ ] All credentials encrypted with ENC(...)
- [ ] FINVANTA_DB_ENCRYPTION_KEY from secrets manager / vault
- [ ] MFA_ENCRYPTION_KEY from secrets manager / vault (separate from DB key)
- [ ] HTTPS/TLS enabled
- [ ] Session cookie secure=true, SameSite=Strict
- [ ] Tomcat default webapps removed
- [ ] SQL Server TLS encryption enabled
- [ ] No default/dev credentials in any log file
