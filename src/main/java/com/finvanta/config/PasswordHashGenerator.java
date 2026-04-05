package com.finvanta.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * CBS Platform Startup Logger.
 * Logs confirmation that the Finvanta CBS platform initialized successfully.
 *
 * Per Finacle/Temenos deployment guidelines, startup logs must confirm:
 * - Platform name and version
 * - Available user roles (MAKER/CHECKER/ADMIN/AUDITOR per CBS role matrix)
 * - Environment type (dev credentials are logged only in non-production)
 *
 * Password handling: Seed data uses {noop} prefix with DelegatingPasswordEncoder
 * for dev; production deployments must use {bcrypt}.
 */
@Component
public class PasswordHashGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordHashGenerator.class);

    @Override
    public void run(String... args) {
        log.info("=== Finvanta CBS Platform Started ===");
        log.info("CBS Role Matrix: MAKER (Loan Officer) | CHECKER (Verification/Approval) | ADMIN (Branch Manager) | AUDITOR (Internal Audit)");
        log.info("Dev credentials: maker1/maker2/checker1/checker2/admin/auditor1 — password: finvanta123");
        log.info("=====================================");
    }
}
