# Finvanta CBS — Deployment & Operations Guide

**Version:** 1.0 | **Classification:** INTERNAL — Operations Team Only

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [SQL Server Database Setup](#2-sql-server-database-setup)
3. [Build the Project](#3-build-the-project)
4. [Profile Configuration](#4-profile-configuration)
5. [Credential Encryption](#5-credential-encryption)
6. [Running in IntelliJ IDEA](#6-running-in-intellij-idea)
7. [Running on Tomcat 10 Server](#7-running-on-tomcat-10-server)
8. [Running with Docker](#8-running-with-docker)
9. [Verifying the Deployment](#9-verifying-the-deployment)
10. [Troubleshooting](#10-troubleshooting)
11. [Security Checklist](#11-security-checklist)

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

```sql
ALTER LOGIN sa ENABLE;
GO
ALTER LOGIN sa WITH PASSWORD = 'sqlserver#123';
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

SSMS → Connect to `localhost,1433` → SQL Server Auth → `sa` / `sqlserver#123`

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
Set as: export FINVANTA_DB_ENCRYPTION_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1
```

Save the 64-character hex key. Use the SAME key for all subsequent steps.

### 5.3 Encrypt Username

Replace `YOUR_KEY` with the key from Step 5.2:

**PowerShell:**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=encrypt YOUR_KEY sa"
```

**Command Prompt:**
```cmd
mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="encrypt YOUR_KEY sa"
```

Output:

```
Encrypted: ENC(base64-ciphertext-here)
Paste this into your properties file.
```

Copy the entire `ENC(...)` value including the wrapper.

### 5.4 Encrypt Password

**PowerShell:**
```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor" "-Dexec.args=encrypt YOUR_KEY sqlserver#123"
```

**Command Prompt:**
```cmd
mvn -q compile exec:java -Dexec.mainClass=com.finvanta.config.CbsPropertyDecryptor -Dexec.args="encrypt YOUR_KEY sqlserver#123"
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

## 6. Running in IntelliJ IDEA

### 6.1 Without Encryption (Quick Start)

1. Set `spring.profiles.active=sqlserver` in `application.properties`
2. Open `FinvantaApplication.java` → click Run
3. Open `http://localhost:8080`

### 6.2 With Encrypted Credentials

1. Complete Section 5 first
2. **Run** → **Edit Configurations** → select `FinvantaApplication`
3. **Environment variables** → click `...` → Add:
   - Name: `FINVANTA_DB_ENCRYPTION_KEY`
   - Value: your 64-char hex key
4. Click **OK** → **OK**
5. Run the application
6. Open `http://localhost:8080`

---

## 7. Running on Tomcat 10 Server

### 7.1 Deploy WAR

```cmd
mvn clean package -DskipTests
copy target\finvanta-0.0.1-SNAPSHOT.war C:\Tomcat10\webapps\ROOT.war
```

### 7.2 Configure Environment

Create `C:\Tomcat10\bin\setenv.bat`:

```bat
@echo off
set "SPRING_PROFILES_ACTIVE=sqlserver"
set "FINVANTA_DB_ENCRYPTION_KEY=your-64-char-hex-key"
set "JAVA_OPTS=%JAVA_OPTS% -Xms512m -Xmx1024m"
```

### 7.3 Start / Stop

```cmd
C:\Tomcat10\bin\startup.bat
C:\Tomcat10\bin\shutdown.bat
```

### 7.4 View Logs

```cmd
type C:\Tomcat10\logs\catalina.out
```

### 7.5 Access

Open `http://localhost:8080`

---

## 8. Running with Docker

### 8.1 Build Image

```cmd
docker build -t finvanta-cbs:latest .
```

### 8.2 Run Container

```cmd
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=sqlserver -e FINVANTA_DB_ENCRYPTION_KEY=your-key finvanta-cbs:latest
```

---

## 9. Verifying the Deployment

| Check | URL / Command | Expected |
|-------|---------------|----------|
| Health | `http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| Login | `http://localhost:8080` → admin/finvanta123 | Dashboard |
| DB Tables | SSMS: `SELECT * FROM app_users` | 6 users |
| Calendar | SSMS: `SELECT * FROM business_calendar WHERE business_date='2026-04-01'` | 3 rows (DAY_OPEN) |

---

## 10. Troubleshooting

| Error | Fix |
|-------|-----|
| `Login failed for user 'sa'` | Enable sa, set password (Section 2.2) |
| `Connection refused port 1433` | Enable TCP/IP (Section 2.4), restart SQL Server |
| `Cannot open database "finvanta"` | Create database (Section 2.1) |
| `CBS property decryption failed` | Check FINVANTA_DB_ENCRYPTION_KEY matches |
| `ENC(...) found but key not set` | Set FINVANTA_DB_ENCRYPTION_KEY env var |
| `Tables not created` | Verify profile is `sqlserver` not `dev` |
| `Port 8080 in use` | Stop other app or change port |

---

## 11. Security Checklist

### Development
- [ ] SQL Server sa has strong password
- [ ] DB credentials encrypted with ENC(...)
- [ ] Encryption key in environment variable only
- [ ] H2 console disabled in sqlserver/prod profiles

### Production
- [ ] Dedicated DB user (not sa) with least privileges
- [ ] All credentials encrypted with ENC(...)
- [ ] Encryption key from secrets manager / vault
- [ ] HTTPS/TLS enabled
- [ ] Session cookie secure=true
- [ ] MFA encryption key from environment variable
- [ ] Tomcat default webapps removed
- [ ] SQL Server TLS encryption enabled
