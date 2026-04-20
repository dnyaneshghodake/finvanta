package com.finvanta.config;

/**
 * CBS Tier-1 OpenAPI Configuration — PLACEHOLDER.
 *
 * <p><b>CBS SECURITY:</b> The springdoc-openapi runtime dependency was removed because
 * both {@code -webmvc-ui} and {@code -webmvc-api} transitively pull
 * {@code io.swagger.core.v3:swagger-core} and {@code swagger-models} which have
 * CRITICAL vulnerabilities:
 * <ul>
 *   <li>CVE-2025-55754 (CVSS 9.6)</li>
 *   <li>CVE-2026-29145 (CVSS 9.1)</li>
 *   <li>CVE-2025-48989 (CVSS 7.5)</li>
 *   <li>CVE-2025-55752 (CVSS 7.5)</li>
 *   <li>GHSA-72hv-8253-57qq (CVSS 7.5)</li>
 *   <li>WS-2026-0003 (CVSS 7.5)</li>
 * </ul>
 *
 * <p>Per RBI IT Governance Direction 2023 §8.2: no runtime dependency with
 * CVSS &gt;= 7.0 is acceptable in a production banking system.
 *
 * <p><b>API Documentation Strategy (zero-runtime-dependency):</b>
 * <ul>
 *   <li>{@code docs/API_REFERENCE.md}: hand-maintained, code-audited reference (106 endpoints)</li>
 *   <li>Build-time generation: use {@code springdoc-openapi-maven-plugin} in CI/CD
 *       to generate static OpenAPI JSON without runtime classpath exposure</li>
 *   <li>Postman collection: import from the generated JSON artifact</li>
 * </ul>
 *
 * <p><b>Reactivation:</b> When swagger-core patches are released for the above CVEs,
 * restore the springdoc dependency and uncomment the {@code @Bean} method below.
 * The OpenAPI metadata (title, version, security scheme) is preserved here for
 * immediate reactivation without re-engineering.
 *
 * <pre>
 * // Restore in pom.xml:
 * // &lt;dependency&gt;
 * //   &lt;groupId&gt;org.springdoc&lt;/groupId&gt;
 * //   &lt;artifactId&gt;springdoc-openapi-starter-webmvc-api&lt;/artifactId&gt;
 * //   &lt;version&gt;{patched-version}&lt;/version&gt;
 * // &lt;/dependency&gt;
 * //
 * // Then uncomment the @Bean method and add imports:
 * // import io.swagger.v3.oas.models.*;
 * // import io.swagger.v3.oas.models.info.*;
 * // import io.swagger.v3.oas.models.security.*;
 * </pre>
 */
public class OpenApiConfig {

    /*
     * @Bean
     * public OpenAPI cbsOpenApi() {
     *     return new OpenAPI()
     *             .info(new Info()
     *                     .title("Finvanta CBS API")
     *                     .description("Tier-1 Core Banking System REST API.")
     *                     .version("v1")
     *                     .contact(new Contact()
     *                             .name("Finvanta CBS Engineering")
     *                             .email("cbs-engineering@finvanta.com"))
     *                     .license(new License()
     *                             .name("Proprietary")
     *                             .url("https://finvanta.com/license")))
     *             .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
     *             .components(new Components()
     *                     .addSecuritySchemes("BearerAuth",
     *                             new SecurityScheme()
     *                                     .type(SecurityScheme.Type.HTTP)
     *                                     .scheme("bearer")
     *                                     .bearerFormat("JWT")
     *                                     .description("CBS JWT access token.")));
     * }
     */
}
