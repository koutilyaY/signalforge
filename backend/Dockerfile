# Multi-stage build. Stage 1 resolves dependencies in its own layer so that
# source-only changes do not re-download the Maven world on every build.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY --from=deps /root/.m2 /root/.m2
COPY pom.xml .
COPY src ./src
# Tests run in CI, not in the image build - keeps `docker compose up` fast and
# avoids requiring a Docker-in-Docker daemon for Testcontainers.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN addgroup -S signalforge && adduser -S signalforge -G signalforge \
    && apk add --no-cache wget

COPY --from=build /build/target/signalforge.jar /app/signalforge.jar
RUN chown -R signalforge:signalforge /app
USER signalforge

EXPOSE 8099

# Container-aware heap sizing; the JVM reads the cgroup limit rather than the
# host's 18GB.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/signalforge.jar"]
