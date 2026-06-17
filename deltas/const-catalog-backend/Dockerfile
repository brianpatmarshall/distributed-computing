# Constants Catalog Backend - Dockerfile
# ======================================
# Multi-stage build for the Spring Boot application.
# Stage 1: Build the application with Maven
# Stage 2: Create a minimal runtime image

# Build stage - use Maven image with JDK 21
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy parent POM and module POMs first for dependency caching
COPY pom.xml ./pom.xml
COPY const-catalog-core/pom.xml ./const-catalog-core/pom.xml
COPY const-catalog-backend/pom.xml ./const-catalog-backend/pom.xml
COPY const-catalog-cli/pom.xml ./const-catalog-cli/pom.xml

# Download dependencies (cached layer)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -pl const-catalog-backend -am -B

# Copy source files
COPY const-catalog-core/src ./const-catalog-core/src
COPY const-catalog-backend/src ./const-catalog-backend/src

# Build the application
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl const-catalog-backend -am -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install git (required for "Create New Branch" transform feature)
# Install gosu (required for entrypoint to drop from root to appuser)
RUN apt-get update && apt-get install -y --no-install-recommends git gosu \
    && rm -rf /var/lib/apt/lists/*

# Copy the built JAR from the builder stage
COPY --from=builder /app/const-catalog-backend/target/*.jar app.jar

# Create a non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Create writable directories owned by appuser
RUN mkdir -p /app/backups /app/logs && chown appuser:appgroup /app/backups /app/logs

# Trust all mounted git repos (ownership differs from appuser) and
# set a system-wide git identity so commits work without ~/.gitconfig
RUN mkdir -p /home/appuser && \
    git config --system --add safe.directory '*' && \
    git config --system user.email "const-catalog@tool.local" && \
    git config --system user.name "Const Catalog"

# Copy entrypoint script that ensures volume-mounted directories are writable
COPY const-catalog-backend/entrypoint.sh /app/entrypoint.sh

# Expose the application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s \
    CMD curl -f http://localhost:8080/api/stats/health || exit 1

# Start as root — entrypoint.sh fixes permissions then drops to appuser via gosu
ENTRYPOINT ["/app/entrypoint.sh"]
