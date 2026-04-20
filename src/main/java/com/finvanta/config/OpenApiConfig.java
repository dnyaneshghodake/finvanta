package com.finvanta.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CBS Tier-1 OpenAPI Configuration per Finacle API / Temenos IRIS / FLEXCUBE REST.
 *
 * <p>Generates OpenAPI 3.0 specification at {@code /v3/api-docs} and Swagger UI
 * at {@code /swagger-ui.html}. Per RBI IT Governance Direction 2023 §8.5:
 * all API endpoints must be documented with request/response schemas,
 * error codes, and security requirements.
 *
 * <p>CBS SECURITY: Swagger UI is accessible in dev/test profiles only.
 * Production disables it via {@code springdoc.swagger-ui.enabled=false}
 * in application-prod.properties. API schema exposure in production is
 * a security risk per OWASP API Security Top 10 (API9:2023).
 *
 * <p>Security scheme: JWT Bearer token per Finacle Connect / Temenos IRIS.
 * All {@code /api/v1/**} endpoints except {@code /api/v1/auth/**} require
 * the {@code Authorization: Bearer {token}} header.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cbsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Finvanta CBS API")
                        .description(
                                "Tier-1 Core Banking System REST API. "
                                + "RBI-compliant, multi-tenant, multi-branch SaaS platform. "
                                + "All financial operations enforce double-entry accounting, "
                                + "maker-checker workflow, and immutable audit trail.")
                        .version("v1")
                        .contact(new Contact()
                                .name("Finvanta CBS Engineering")
                                .email("cbs-engineering@finvanta.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://finvanta.com/license")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "CBS JWT access token. Obtain via "
                                                + "POST /api/v1/auth/token. "
                                                + "Include as: Authorization: Bearer {token}")));
    }
}
