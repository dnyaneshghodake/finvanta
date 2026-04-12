package com.finvanta.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * CBS Platform Startup Logger per Finacle/Temenos deployment guidelines.
 *
 * Logs platform startup confirmation with profile-aware behavior:
 * - DEV: Shows role matrix + dev credential hint (H2 seed data users)
 * - SQLSERVER/PROD: Shows role matrix ONLY — NEVER logs credentials
 *
 * Per RBI IT Governance Direction 2023 §8.2:
 * Default/well-known credentials must NEVER appear in production logs.
 * Credential logging in dev is acceptable for developer convenience
 * since H2 resets on every restart and is not network-accessible.
 */
@Component
public class CbsStartupLogger implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CbsStartupLogger.class);

    private final Environment environment;

    public CbsStartupLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        log.info("=== Finvanta CBS Platform Started ===");
        log.info("CBS Role Matrix: MAKER (Loan Officer) | CHECKER (Verification/Approval) "
                + "| ADMIN (Branch Manager) | AUDITOR (Internal Audit)");

        // CBS Security: Only show dev credentials on dev profile (H2 in-memory).
        // Per RBI IT Governance §8.2: credentials must NEVER appear in non-dev logs.
        if (environment.matchesProfiles("dev")) {
            log.info("Dev credentials: maker1/maker2/checker1/checker2/admin/auditor1"
                    + " — password: finvanta123");
        }

        log.info("=====================================");
    }
}
