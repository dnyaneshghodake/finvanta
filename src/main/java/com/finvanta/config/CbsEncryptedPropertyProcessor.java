package com.finvanta.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * CBS Encrypted Property Processor per RBI IT Governance Direction 2023.
 *
 * Intercepts Spring Boot property loading and decrypts any value wrapped in
 * ENC(base64-ciphertext) using AES-256-GCM. The decryption key comes from
 * the environment variable FINVANTA_DB_ENCRYPTION_KEY.
 *
 * This runs BEFORE Spring creates the DataSource, so encrypted DB credentials
 * in property files are transparently decrypted at startup.
 *
 * Registration: META-INF/spring.factories (EnvironmentPostProcessor SPI)
 *
 * Per Finacle/Temenos: credentials at rest must be encrypted. The key must
 * be external to the application artifact (environment variable / KMS / HSM).
 *
 * If no ENC(...) properties are found, this processor is a no-op.
 * If ENC(...) properties are found but the key is missing, startup fails
 * with a clear error message.
 */
public class CbsEncryptedPropertyProcessor implements EnvironmentPostProcessor {

    private static final String KEY_ENV_VAR = "FINVANTA_DB_ENCRYPTION_KEY";
    private static final String DECRYPTED_SOURCE_NAME = "cbsDecryptedProperties";

    /** Properties to scan for ENC(...) values. */
    private static final String[] SENSITIVE_PROPERTIES = {
            "spring.datasource.username",
            "spring.datasource.password",
            "mfa.encryption.key"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> decrypted = new HashMap<>();
        String encryptionKey = null;
        boolean keyResolved = false;

        for (String propName : SENSITIVE_PROPERTIES) {
            String value;
            try {
                value = environment.getProperty(propName);
            } catch (IllegalArgumentException e) {
                // Property contains unresolvable placeholder (e.g., ${SPRING_DATASOURCE_USERNAME}
                // from prod profile when env vars aren't set). Skip — not an ENC(...) value.
                continue;
            }
            if (value != null && CbsPropertyDecryptor.isEncrypted(value)) {
                // Lazy-resolve key only when ENC(...) values are found
                if (!keyResolved) {
                    try {
                        encryptionKey = environment.getProperty(KEY_ENV_VAR);
                    } catch (IllegalArgumentException e) {
                        encryptionKey = null;
                    }
                    if (encryptionKey == null || encryptionKey.isBlank()) {
                        encryptionKey = System.getenv(KEY_ENV_VAR);
                    }
                    if (encryptionKey == null || encryptionKey.isBlank()) {
                        throw new IllegalStateException(
                                "CBS SECURITY: Found ENC(...) encrypted properties but "
                                        + KEY_ENV_VAR + " environment variable is not set. "
                                        + "Set it with: export " + KEY_ENV_VAR + "=$(openssl rand -hex 32)");
                    }
                    keyResolved = true;
                }

                String ciphertext = CbsPropertyDecryptor.unwrap(value);
                String plaintext = CbsPropertyDecryptor.decrypt(ciphertext, encryptionKey);
                decrypted.put(propName, plaintext);
            }
        }

        if (!decrypted.isEmpty()) {
            // Add decrypted values as highest-priority property source
            // This overrides the ENC(...) values from property files
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(DECRYPTED_SOURCE_NAME, decrypted));
        }
    }
}
