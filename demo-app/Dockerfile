# =============================================================================
# Demo App — multi-stage Docker build
#
# Build context: repo root (.)
# Called from docker-compose as:
#   build:
#     context: .
#     dockerfile: demo-app/Dockerfile
#
# Stages:
#   agent-build  — compiles jacoco-otel-agent with JDK 8 (compatibility requirement)
#   app-build    — compiles the Spring Boot demo app with JDK 17
#   runtime      — minimal JRE image with all jars baked in
# =============================================================================

# Stage 1: Build the coverage agent jar (must target JDK 8)
FROM maven:3.9.6-eclipse-temurin-8 AS agent-build

WORKDIR /build
COPY jacoco-otel-agent/pom.xml .
RUN mvn dependency:go-offline -B -q
COPY jacoco-otel-agent/src/ src/
RUN mvn package -DskipTests -B -q

# Stage 2: Build the Spring Boot demo app
FROM maven:3.9.6-eclipse-temurin-17 AS app-build

WORKDIR /build
COPY demo-app/pom.xml .
RUN mvn dependency:go-offline -B -q
COPY demo-app/src/ src/
RUN mvn package -DskipTests -B -q

# Stage 3: Runtime image
FROM eclipse-temurin:17-jre

WORKDIR /app

# Application jar
COPY --from=app-build /build/target/demo-app-*.jar app.jar

# JaCoCo agent (fetched as a Maven dependency in app-build)
COPY --from=app-build /build/target/agents/jacoco-agent.jar /agents/jacoco-agent.jar

# OTel zero-code Java agent (fetched as a Maven dependency in app-build)
COPY --from=app-build /build/target/agents/otel-agent.jar /agents/otel-agent.jar

# Our coverage agent (compiled in agent-build)
COPY --from=agent-build /build/target/jacoco-otel-agent-*.jar /agents/jacoco-otel-agent.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-javaagent:/agents/jacoco-agent.jar=output=none,includes=io.jacoco.demo.*", \
  "-javaagent:/agents/jacoco-otel-agent.jar", \
  "-jar", "app.jar"]
