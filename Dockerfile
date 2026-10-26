# ─────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────
FROM amazoncorretto:21-alpine AS builder

WORKDIR /workspace

# Copy Gradle wrapper and dependency descriptors first (layer-cache friendly)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Download dependencies without compiling sources
RUN ./gradlew dependencies --no-daemon -q

# Copy source and build the fat-jar
COPY src ./src
RUN ./gradlew bootJar --no-daemon -q

# ─────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────
FROM amazoncorretto:21-alpine

WORKDIR /app

# Non-root user for security
RUN addgroup -S nabu && adduser -S nabu -G nabu

COPY --from=builder /workspace/build/libs/nabu-watch.jar app.jar

RUN chown nabu:nabu app.jar
USER nabu

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=40s \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
