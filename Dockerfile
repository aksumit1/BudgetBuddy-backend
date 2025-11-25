# Multi-stage build for BudgetBuddy Backend
# Optimized for AWS Graviton2 (ARM64) processors - 20% cost savings

# Build stage - Use ARM64 base image with JDK 21
FROM --platform=linux/arm64 maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage - Use ARM64/Graviton2 base image with JDK 21
FROM --platform=linux/arm64 eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Health check for ECS
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# Use exec form for better signal handling
# JDK 21 optimizations: ZGC for low latency, optimized container support
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-XX:+UnlockExperimentalVMOptions", \
  "-XX:+UseTransparentHugePages", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
