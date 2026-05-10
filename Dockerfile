# Multi-stage build for BudgetBuddy Backend
# Optimized for AWS Graviton2 (ARM64) processors - 20% cost savings

# Build stage - Java 25 LTS on ARM64/Graviton2.
ARG BUILDPLATFORM=linux/arm64
FROM --platform=${BUILDPLATFORM} maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy checkstyle.xml for code quality checks
COPY checkstyle.xml .

# Copy source code and build
COPY src ./src

# Copy scripts for downloading BERT model
COPY scripts/download-bert-model.sh ./scripts/
RUN chmod +x ./scripts/download-bert-model.sh

# Download DistilBERT model during build (optional - can also download at runtime)
# Uncomment the following line to include model in Docker image:
# RUN ./scripts/download-bert-model.sh models || echo "Model download skipped (will download at runtime)"

RUN mvn clean package -DskipTests

# Runtime stage - Java 25 LTS on ARM64/Graviton2.
ARG TARGETPLATFORM=linux/arm64
FROM --platform=${TARGETPLATFORM} eclipse-temurin:25-jre-alpine

WORKDIR /app

# Install Tesseract OCR and language data files (FREE OCR for AWS)
# Tesseract is open-source and free, works perfectly on AWS ECS/Fargate
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    tesseract-ocr-data-deu \
    tesseract-ocr-data-fra \
    tesseract-ocr-data-spa \
    tesseract-ocr-data-ita \
    tesseract-ocr-data-por \
    tesseract-ocr-data-rus \
    tesseract-ocr-data-chi_sim \
    tesseract-ocr-data-chi_tra \
    tesseract-ocr-data-jpn \
    tesseract-ocr-data-kor \
    tesseract-ocr-data-ara \
    tesseract-ocr-data-hin \
    tesseract-ocr-data-nld \
    tesseract-ocr-data-pol \
    tesseract-ocr-data-tur \
    tesseract-ocr-data-vie \
    tesseract-ocr-data-tha \
    tesseract-ocr-data-ind \
    tesseract-ocr-data-msa \
    && mkdir -p /usr/share/tesseract-ocr/5/tessdata \
    && ln -s /usr/share/tesseract-ocr/tessdata /usr/share/tesseract-ocr/5/tessdata || true

# Set TESSDATA_PREFIX environment variable for Tesseract
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# Verify Tesseract installation
RUN tesseract --version || echo "Tesseract installation verification"

# Install AWS CLI for downloading model from S3 at runtime (if needed)
RUN apk add --no-cache \
    aws-cli \
    curl \
    bash

# Create models directory
RUN mkdir -p /app/models

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Copy model download script (for runtime download from S3)
COPY --from=build /app/scripts/download-bert-model.sh /app/scripts/
RUN chmod +x /app/scripts/download-bert-model.sh

# Copy entrypoint script that downloads model from S3 if needed
COPY scripts/docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

USER spring:spring

# Health check for ECS
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# Use entrypoint script that handles model download before starting app
ENTRYPOINT ["/app/docker-entrypoint.sh"]
