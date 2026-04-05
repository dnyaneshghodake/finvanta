package com.finvanta.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Application startup verification.
 * Logs confirmation that the CBS platform initialized successfully.
 * No runtime password manipulation — seed data uses {noop} prefix
 * with DelegatingPasswordEncoder for dev, {bcrypt} for production.
 */
@Component
public class PasswordHashGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordHashGenerator.class);

    @Override
    public void run(String... args) {
        log.info("=== Finvanta CBS Platform Started ===");
        log.info("Dev credentials: maker1/checker1/admin/auditor1 — password: finvanta123");
        log.info("=====================================");
    }
}
