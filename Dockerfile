# Multi-stage build: compile with Maven, run on a slim JRE image.
# Builder tag verified to exist (maven:3.9-eclipse-temurin-21, 2026-10).

# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Restored as a separate layer so dependency downloads are cached on top of the
# wrapper copy; works with the Maven wrapper (no pre-installed POM plugin needed
# because the parent POM resolution also goes through the repository).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline || true

COPY src/ src/
RUN ./mvnw -B -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root runtime user.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
USER appuser

COPY --from=build /workspace/target/auditbox-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
