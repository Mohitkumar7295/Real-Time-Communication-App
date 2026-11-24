# ==============================================================================
# MeetApp — Production Multi-Stage Dockerfile
# Stage 1: Build application JAR with Maven and Eclipse Temurin 17 JDK
# Stage 2: Minimal, non-root Eclipse Temurin 17 JRE Alpine runtime image
# ==============================================================================

# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Copy settings, pom.xml, and source code to build production artifact
COPY maven-settings.xml ./
COPY pom.xml ./
COPY src ./src
RUN mvn -s maven-settings.xml clean package -DskipTests -B

# ------------------------------------------------------------------------------
# Stage 2: Runtime
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runner

# Create non-root application user and group (UID/GID 10001)
RUN addgroup -S meetapp -g 10001 && \
    adduser -S meetapp -u 10001 -G meetapp -h /app

WORKDIR /app

# Create uploads directory and set permissions
RUN mkdir -p /app/uploads && \
    chown -R meetapp:meetapp /app

# Copy compiled JAR from builder stage
COPY --from=builder --chown=meetapp:meetapp /build/target/meetapp-*.jar /app/meetapp.jar

USER meetapp:meetapp

# Render passes dynamic port via PORT environment variable (default 8080)
ENV PORT=8080
EXPOSE 8080

# Production JVM options:
# - MaxRAMPercentage=75.0 leaves headroom for Metaspace/OS buffers in 512 MB container
# - Dynamic binding to ${PORT} supplied by Render
# - Non-blocking secure random number generation
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75.0 -Dserver.port=${PORT:-8080} -Djava.security.egd=file:/dev/./urandom -jar /app/meetapp.jar"]

