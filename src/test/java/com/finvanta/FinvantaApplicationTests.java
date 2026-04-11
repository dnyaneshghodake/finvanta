package com.finvanta;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * CBS Context Load Test — verifies the full Spring Boot application context starts.
 *
 * Per Tier-1 CBS: must use 'test' profile to avoid loading sqlserver/prod properties
 * that contain ENC(...) encrypted values requiring FINVANTA_DB_ENCRYPTION_KEY env var.
 * The CbsEncryptedPropertyProcessor (EnvironmentPostProcessor) runs before Spring
 * context creation and fails startup if ENC(...) values are found without the key.
 */
@SpringBootTest
@ActiveProfiles("test")
class FinvantaApplicationTests {

    @Test
    void contextLoads() {}
}
