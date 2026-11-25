# syntax=docker/dockerfile:1
# Multi-stage build: compile with the JDK, run on the JRE as a non-root user.
# Tests are not run here - CI runs them (scripts/ci/api.sh); this stage only packages the jar.
FROM eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test \
 && cp build/libs/*.jar /src/app.jar

FROM eclipse-temurin:21.0.13_9-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6 AS runtime
ARG GIT_SHA=unknown
ENV APP_GIT_SHA=${GIT_SHA} \
    ATTACHMENTS_DIR=/data/attachments \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app /data/attachments \
 && chown -R app:app /app /data
WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar /app/app.jar
USER app:app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
