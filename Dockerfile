# Multi-stage Dockerfile for TD Enrich Service
# Matches pom.xml <java.version>17</java.version> — use Java 17 in both stages.

# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom.xml first — Docker caches this layer until pom.xml changes,
# so repeated builds skip the dependency download step.
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

RUN chmod +x mvnw && ./mvnw dependency:go-offline

COPY src ./src

# Skip tests here — tests run in CI before the image is built.
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime — JRE only, no compiler tools in the final image.
FROM eclipse-temurin:17-jre-alpine

# curl is required by the HEALTHCHECK below.
RUN apk add --no-cache curl

# Run as a non-root user — reduces blast radius if the container is compromised.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

# JVM tuning for a long-running containerized service on Java 17.
# Notes on removed flags:
#   -XX:+TieredCompilation and -XX:TieredStopAtLevel=1 were removed — they disable the
#   C2 JIT compiler, which is only appropriate for short-lived Lambda/CLI processes.
#   For a persistent service handling sustained load, full tiered compilation (the default)
#   produces significantly better throughput after warm-up.
ENV JAVA_OPTS="\
    -XX:+UseG1GC \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseContainerSupport \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.backgroundpreinitializer.ignore=true"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
