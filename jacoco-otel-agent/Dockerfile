# =============================================================================
# JaCoCo OTel Agent — Docker build (JDK 8 compatible)
#
# Usage:
#   # Build the agent jar and export it to ./agents/ on the host
#   docker build -f jacoco-otel-agent/Dockerfile \
#                --target export \
#                -o ./agents \
#                ./jacoco-otel-agent
#
# The exported file will be: ./agents/jacoco-otel-agent.jar
# =============================================================================

# Stage 1: compile + package with JDK 8
FROM maven:3.9.6-eclipse-temurin-8 AS build

WORKDIR /build

# Cache dependencies before copying source
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Build the shaded uber-jar
COPY src/ src/
RUN mvn package -DskipTests -B -q

# Stage 2: scratch-based export — produces just the jar on the host
FROM scratch AS export
COPY --from=build /build/target/jacoco-otel-agent-*.jar /jacoco-otel-agent.jar
