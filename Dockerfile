# ============================================================
# Finvanta CBS — Tomcat 10 Docker Image
# Tier-1 Core Banking System — RBI Compliant
# ============================================================
#
# Build:  docker build -t finvanta-cbs:latest .
# Run:    docker run -p 8080:8080 \
#           -e SPRING_PROFILES_ACTIVE=prod \
#           -e SPRING_DATASOURCE_URL="jdbc:sqlserver://dbhost:1433;databaseName=finvanta;encrypt=true" \
#           -e SPRING_DATASOURCE_USERNAME=finvanta_app \
#           -e SPRING_DATASOURCE_PASSWORD=<secret> \
#           -e MFA_ENCRYPTION_KEY=$(openssl rand -hex 32) \
#           finvanta-cbs:latest
#
# Per RBI IT Governance Direction 2023:
# - Non-root container execution
# - No default/dev credentials in image
# - TLS enforced via external load balancer
# ============================================================

# === Stage 1: Build WAR ===
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
# Download dependencies first (Docker layer cache optimization)
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# === Stage 2: Runtime on Tomcat 10 ===
FROM tomcat:10.1-jdk17-temurin-jammy

# CBS Security: Remove default Tomcat webapps (manager, examples, docs)
RUN rm -rf /usr/local/tomcat/webapps/*

# Deploy WAR as ROOT application
COPY --from=builder /build/target/*.war /usr/local/tomcat/webapps/ROOT.war

# CBS Security: Run as non-root user per RBI IT Governance
RUN groupadd -r finvanta && useradd -r -g finvanta finvanta \
    && chown -R finvanta:finvanta /usr/local/tomcat
USER finvanta

# Health check for container orchestration (Docker/K8s)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
CMD ["catalina.sh", "run"]
