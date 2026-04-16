package com.finvanta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * CBS Application Entry Point per Finacle/Temenos Tier-1 bootstrap standards.
 *
 * <p>Extends {@link SpringBootServletInitializer} so the application can be
 * deployed as a WAR to an external Tomcat 10.1+ container (production) while
 * retaining the embedded Tomcat path for development ({@code mvn spring-boot:run}).
 *
 * <p><b>Why this is required:</b> when deployed as a WAR, the Servlet container
 * (Tomcat) does NOT call {@code main()}. Instead, it discovers
 * {@code SpringBootServletInitializer} via the Servlet 5.0 SCI (Service
 * Provider Interface) and calls {@link #configure(SpringApplicationBuilder)}.
 * Without this, Tomcat deploys the WAR as an empty webapp — no Spring context,
 * no beans, no controllers, no logs. This is exactly the failure mode observed
 * when the application was first deployed to external Tomcat.
 *
 * <p>Per RBI IT Governance Direction 2023 §7.1: production CBS deployments
 * must use an externally managed application server (not embedded) so that
 * the ops team can manage JVM lifecycle, heap dumps, and thread dumps
 * independently of the application.
 */
@SpringBootApplication
@EnableAsync
public class FinvantaApplication extends SpringBootServletInitializer {

    /**
     * External Tomcat entry point. Called by the Servlet container's SCI
     * mechanism when the WAR is deployed. Configures the Spring application
     * context using the same source class as {@link #main(String[])}.
     */
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(FinvantaApplication.class);
    }

    /**
     * Embedded Tomcat entry point. Used during development with
     * {@code mvn spring-boot:run} or {@code java -jar finvanta.war}.
     */
    public static void main(String[] args) {
        SpringApplication.run(FinvantaApplication.class, args);
    }
}
