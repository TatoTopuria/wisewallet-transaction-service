# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG OTEL_AGENT_VERSION=2.12.0

WORKDIR /app

# Download OTel agent
RUN apk add --no-cache curl \
    && curl -fsSL \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
       -o /app/otel-agent.jar \
    && apk del curl

# Non-root user
RUN addgroup -S wisewallet && adduser -S wisewallet -G wisewallet

COPY --from=builder /workspace/build/libs/*.jar app.jar
RUN chown -R wisewallet:wisewallet /app

USER wisewallet

EXPOSE 8082 8092

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8092/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-javaagent:/app/otel-agent.jar", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
  "-jar", "app.jar"]
