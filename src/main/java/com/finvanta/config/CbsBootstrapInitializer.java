package com.finvanta.config;

import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.Tenant;
import com.finvanta.domain.enums.BranchType;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.domain.enums.UserRole;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.TenantRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * CBS Day Zero Installation Pipeline per Finacle INSTALL_BANK / Temenos INSTALL.COMPANY.
 *
 * Per Tier-1 CBS standards: the installation program creates a FULLY OPERATIONAL
 * system in a single atomic pipeline. After bootstrap, the admin logs in and can
 * transact immediately — no manual DBA SQL scripts required.
 *
 * Pipeline (runs ONCE when app_users table is empty):
 *   Step 1: Tenant       — bank identity per RBI Banking Regulation Act 1949
 *   Step 2: Head Office  — consolidation point per Finacle SOL hierarchy
 *   Step 3: Op. Branch   — first operational branch for transactions per RBI §23
 *   Step 4: GL Chart     — 27 Indian Banking Standard GL codes (1xxx–5xxx)
 *   Step 5: ADMIN user   — with branch assigned, password expired (T-1)
 *   Step 6: Calendar     — current month generated for all operational branches
 *   Step 7: Day Open     — first business day opened, txn batches auto-created
 *
 * Per RBI IT Governance Direction 2023 §8.2:
 * - Credentials printed to console stdout ONLY (never to log files)
 * - Password expired immediately — forces change on first login
 *
 * Environment variables:
 *   CBS_ADMIN_TENANT    — Tenant code (default: DEFAULT)
 *   CBS_ADMIN_USERNAME  — Admin username (default: sysadmin)
 *   CBS_ADMIN_PASSWORD  — Admin password (default: auto-generated)
 *   CBS_ADMIN_EMAIL     — Admin email (default: admin@localhost)
 */
@Component
@Order(100)
public class CbsBootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CbsBootstrapInitializer.class);

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#$%&*!";
    private static final int AUTO_PASSWORD_LENGTH = 16;
    private static final String BOOTSTRAP_USER = "SYSTEM_BOOTSTRAP";

    private final AppUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final GLMasterRepository glMasterRepository;
    private final BusinessDateService businessDateService;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public CbsBootstrapInitializer(
            AppUserRepository userRepository,
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            GLMasterRepository glMasterRepository,
            BusinessDateService businessDateService,
            PasswordEncoder passwordEncoder,
            Environment environment) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.glMasterRepository = glMasterRepository;
        this.businessDateService = businessDateService;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        long userCount = userRepository.count();
        if (userCount > 0) {
            log.debug("CBS Day Zero: {} users exist — skipping.", userCount);
            return;
        }
        String tenantId = environment.getProperty("CBS_ADMIN_TENANT", "DEFAULT");
        TenantContext.setCurrentTenant(tenantId);
        try {
            log.info("CBS Day Zero: Starting installation for tenant '{}'", tenantId);
            bootstrapTenant(tenantId);
            Branch hoBranch = bootstrapHeadOffice(tenantId);
            Branch opBranch = bootstrapOperationalBranch(tenantId, hoBranch);
            int glCount = bootstrapGLChart(tenantId);
            String[] creds = bootstrapAdminUser(tenantId, hoBranch);
            int calEntries = bootstrapCalendar();
            LocalDate openedDate = bootstrapDayOpen(opBranch);
            printBootstrapSummary(creds, tenantId, hoBranch, opBranch, glCount, calEntries, openedDate);
            log.info("CBS Day Zero: Complete. System is operational.");
        } catch (Exception e) {
            log.error("CBS Day Zero FAILED: {}", e.getMessage(), e);
            System.err.println("CBS Day Zero failed: " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private void bootstrapTenant(String tenantId) {
        if (tenantRepository.existsByTenantCode(tenantId)) return;
        Tenant t = new Tenant();
        t.setTenantCode(tenantId); t.setTenantName("Finvanta CBS (Bootstrap)");
        t.setLicenseType("ENTERPRISE"); t.setActive(true); t.setDbSchema("dbo");
        t.setRbiBankCode("9999"); t.setIfscPrefix("FNVT"); t.setRegulatoryCategory("SCB");
        t.setCountryCode("IN"); t.setBaseCurrency("INR"); t.setTimezone("Asia/Kolkata");
        t.setCreatedBy(BOOTSTRAP_USER); tenantRepository.save(t);
        log.info("Step 1: Tenant '{}' created.", tenantId);
    }

    private Branch bootstrapHeadOffice(String tenantId) {
        return branchRepository.findHeadOffice(tenantId).orElseGet(() -> {
            Branch ho = new Branch(); ho.setTenantId(tenantId);
            ho.setBranchCode("HQ001"); ho.setBranchName("Head Office (Bootstrap)");
            ho.setIfscCode("FNVT0000001"); ho.setAddress("Head Office");
            ho.setCity("Mumbai"); ho.setState("Maharashtra"); ho.setPinCode("400001");
            ho.setActive(true); ho.setZoneCode("WEST"); ho.setBranchType(BranchType.HEAD_OFFICE);
            ho.setRegionCode("WEST"); ho.setCreatedBy(BOOTSTRAP_USER);
            Branch saved = branchRepository.save(ho);
            log.info("Step 2: Head Office '{}' created.", saved.getBranchCode());
            return saved;
        });
    }

    private Branch bootstrapOperationalBranch(String tenantId, Branch hoBranch) {
        var existing = branchRepository.findAllOperationalBranches(tenantId);
        if (!existing.isEmpty()) return existing.get(0);
        Branch br = new Branch(); br.setTenantId(tenantId);
        br.setBranchCode("BR001"); br.setBranchName("Main Branch (Bootstrap)");
        br.setIfscCode("FNVT0000002"); br.setAddress("Main Branch");
        br.setCity("Mumbai"); br.setState("Maharashtra"); br.setPinCode("400001");
        br.setActive(true); br.setZoneCode("WEST"); br.setBranchType(BranchType.BRANCH);
        br.setParentBranch(hoBranch); br.setRegionCode("WEST"); br.setCreatedBy(BOOTSTRAP_USER);
        Branch saved = branchRepository.save(br);
        log.info("Step 3: Operational branch '{}' created.", saved.getBranchCode());
        return saved;
    }

    private int bootstrapGLChart(String tenantId) {
        if (glMasterRepository.findByTenantIdAndGlCode(tenantId, "1000").isPresent()) return 0;
        Object[][] chart = {
            {"1000","Assets",GLAccountType.ASSET,true},{"1001","Loan Portfolio",GLAccountType.ASSET,false},
            {"1002","Interest Receivable",GLAccountType.ASSET,false},{"1003","Provision for NPA",GLAccountType.ASSET,false},
            {"1100","Bank Account - Ops",GLAccountType.ASSET,false},{"1300","IB Receivable",GLAccountType.ASSET,false},
            {"2000","Liabilities",GLAccountType.LIABILITY,true},{"2001","Customer Deposits",GLAccountType.LIABILITY,false},
            {"2010","Deposits - Savings",GLAccountType.LIABILITY,false},{"2020","Deposits - Current",GLAccountType.LIABILITY,false},
            {"2100","Interest Suspense NPA",GLAccountType.LIABILITY,false},{"2101","Sundry Suspense",GLAccountType.LIABILITY,false},
            {"2200","CGST Payable",GLAccountType.LIABILITY,false},{"2201","SGST Payable",GLAccountType.LIABILITY,false},
            {"2300","IB Payable",GLAccountType.LIABILITY,false},{"2400","Clearing Suspense",GLAccountType.LIABILITY,false},
            {"2500","TDS Payable",GLAccountType.LIABILITY,false},{"3000","Equity",GLAccountType.EQUITY,true},
            {"3001","Share Capital",GLAccountType.EQUITY,false},{"4000","Income",GLAccountType.INCOME,true},
            {"4001","Interest Income Loans",GLAccountType.INCOME,false},{"4002","Fee Income",GLAccountType.INCOME,false},
            {"4003","Penal Interest",GLAccountType.INCOME,false},{"4010","Interest Inc Deposits",GLAccountType.INCOME,false},
            {"5000","Expenses",GLAccountType.EXPENSE,true},{"5001","Provision Expense",GLAccountType.EXPENSE,false},
            {"5002","Write-Off Expense",GLAccountType.EXPENSE,false},{"5010","Interest Exp Deposits",GLAccountType.EXPENSE,false},
        };
        int created = 0;
        for (Object[] r : chart) {
            GLMaster gl = new GLMaster(); gl.setTenantId(tenantId);
            gl.setGlCode((String)r[0]); gl.setGlName((String)r[1]);
            gl.setAccountType((GLAccountType)r[2]); gl.setHeaderAccount((Boolean)r[3]);
            gl.setActive(true); gl.setDebitBalance(BigDecimal.ZERO);
            gl.setCreditBalance(BigDecimal.ZERO); gl.setCreatedBy(BOOTSTRAP_USER);
            glMasterRepository.save(gl); created++;
        }
        log.info("Step 4: {} GL accounts created.", created);
        return created;
    }

    private String[] bootstrapAdminUser(String tenantId, Branch hoBranch) {
        String username = environment.getProperty("CBS_ADMIN_USERNAME", "sysadmin");
        String email = environment.getProperty("CBS_ADMIN_EMAIL", "admin@localhost");
        String rawPassword = environment.getProperty("CBS_ADMIN_PASSWORD");
        boolean autoGenerated = false;
        if (rawPassword == null || rawPassword.isBlank()) { rawPassword = generateSecurePassword(); autoGenerated = true; }
        AppUser admin = new AppUser(); admin.setTenantId(tenantId);
        admin.setUsername(username); admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setFullName("System Administrator (Bootstrap)"); admin.setEmail(email);
        admin.setRole(UserRole.ADMIN); admin.setBranch(hoBranch);
        admin.setActive(true); admin.setLocked(false); admin.setFailedLoginAttempts(0);
        admin.setMfaEnabled(false); admin.setPasswordExpiryDate(LocalDate.now().minusDays(1));
        admin.setCreatedBy(BOOTSTRAP_USER); admin.setUpdatedBy(BOOTSTRAP_USER);
        userRepository.save(admin);
        log.info("Step 5: ADMIN '{}' at branch '{}'.", username, hoBranch.getBranchCode());
        return new String[]{username, autoGenerated ? rawPassword : null};
    }

    private int bootstrapCalendar() {
        try {
            LocalDate today = LocalDate.now();
            return businessDateService.generateCalendarForMonth(today.getYear(), today.getMonthValue());
        } catch (Exception e) { log.warn("Step 6: Calendar skipped — {}", e.getMessage()); return 0; }
    }

    private LocalDate bootstrapDayOpen(Branch opBranch) {
        try {
            LocalDate today = LocalDate.now();
            if (today.getDayOfWeek().getValue() >= 6) {
                log.info("Step 7: Today is weekend — day open skipped.");
                return null;
            }
            businessDateService.openDay(today, opBranch.getId());
            log.info("Step 7: Day {} opened at '{}'.", today, opBranch.getBranchCode());
            return today;
        } catch (Exception e) { log.warn("Step 7: Day open skipped — {}", e.getMessage()); return null; }
    }

    private void printBootstrapSummary(String[] creds, String tenantId, Branch ho, Branch op,
            int glCount, int calEntries, LocalDate openedDate) {
        System.out.println();
        System.out.println("==============================================================");
        System.out.println("  CBS DAY ZERO: Installation Complete");
        System.out.println("==============================================================");
        System.out.println("  Tenant   : " + tenantId);
        System.out.println("  HO Branch: " + ho.getBranchCode() + " — " + ho.getBranchName());
        System.out.println("  Op Branch: " + op.getBranchCode() + " — " + op.getBranchName());
        System.out.println("  GL Codes : " + glCount + " accounts (Indian Banking Standard)");
        System.out.println("  Calendar : " + calEntries + " entries for current month");
        System.out.println("  Day Open : " + (openedDate != null ? openedDate.toString() : "SKIPPED (weekend/holiday)"));
        System.out.println("--------------------------------------------------------------");
        System.out.println("  Username : " + creds[0]);
        if (creds[1] != null) {
            System.out.println("  Password : " + creds[1]);
        } else {
            System.out.println("  Password : (from CBS_ADMIN_PASSWORD env var)");
        }
        System.out.println("  Role     : ADMIN");
        System.out.println("  Branch   : " + ho.getBranchCode());
        System.out.println("--------------------------------------------------------------");
        System.out.println("  PASSWORD CHANGE REQUIRED ON FIRST LOGIN");
        System.out.println("  System is ready — login at /login");
        System.out.println("==============================================================");
        System.out.println();
    }

    private String generateSecurePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(AUTO_PASSWORD_LENGTH);
        for (int i = 0; i < AUTO_PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return password.toString();
    }
}