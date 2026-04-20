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
 * <p>Generates OpenAPI 3.0 specification at {@code /v3/api-docs}.
 * Per RBI IT Governance Direction 2023 §8.5: all API endpoints must be
 * documented with request/response schemas, error codes, and security
 * requirements.
 *
 * <p>CBS SECURITY: The Swagger UI webjar is NOT included in the dependency tree.
 * The {@code springdoc-openapi-starter-webmvc-ui} artifact was replaced with
 * {@code springdoc-openapi-starter-webmvc-api} to eliminate transitive
 * vulnerabilities in the swagger-ui JavaScript (GHSA-72hv-8253-57qq,
 * WS-2026-0003 — CVSS 7.5). API documentation is consumed via:
 * <ul>
 *   <li>Postman: import from {@code http://localhost:8080/v3/api-docs}</li>
 *   <li>Swagger Editor (external): paste the JSON spec</li>
 *   <li>CI/CD: generate static docs from the OpenAPI JSON artifact</li>
 * </ul>
 *
 * <p>Production disables the spec endpoint via {@code springdoc.api-docs.enabled=false}
 * in application-prod.properties per OWASP API Security Top 10 (API9:2023).
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
