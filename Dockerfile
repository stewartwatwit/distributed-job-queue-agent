# syntax=docker/dockerfile:1

# ---- build stage ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI (build-and-test job), not during the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/distributed-job-queue-*.jar /build/app.jar

# ---- runtime stage --------------------------------------------------------
# Alpine variant: busybox wget is available for the healthcheck (no curl needed).
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
USER app
WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar

EXPOSE 8080

# Size the heap from the container memory limit; exit on OOM so the orchestrator restarts us.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

# Exec form: the JVM is PID 1 and receives SIGTERM, so graceful shutdown works.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
