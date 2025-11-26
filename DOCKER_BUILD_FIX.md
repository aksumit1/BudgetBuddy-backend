# Docker Build Fix for Local Development

## 🚨 Issue

**Problem**: `docker-compose` failed to build the backend with error:
```
Fatal error compiling: error: release version 25 not supported
```

**Root Cause**: 
- The project uses Java 25 (`pom.xml` has `<java.version>25</java.version>`)
- The Dockerfile was using JDK 21 image (`maven:3.9-eclipse-temurin-21`)
- Java 25 Docker images may not be available yet or may not work on all platforms

---

## ✅ Solution

Created `Dockerfile.local` that:
1. Uses Java 21 for local development (stable and widely available)
2. Temporarily modifies `pom.xml` during build to use Java 21
3. Builds successfully with Java 21
4. Runs with JRE 21

### Changes Made

1. **Created `Dockerfile.local`**:
   - Uses `maven:3.9-eclipse-temurin-21` for build
   - Uses `eclipse-temurin:21-jre-alpine` for runtime
   - Uses `sed` to temporarily modify `pom.xml` to use Java 21

2. **Updated `docker-compose.yml`**:
   - Changed `dockerfile: Dockerfile` to `dockerfile: Dockerfile.local`

---

## 📋 Usage

### Build and Start Services

```bash
cd BudgetBuddy-Backend

# Build the backend image
docker-compose build backend

# Start all services (LocalStack + Backend)
docker-compose up -d

# Check health
curl http://localhost:8080/actuator/health

# View logs
docker-compose logs -f backend

# Stop services
docker-compose down
```

---

## 🔍 How It Works

The `Dockerfile.local` uses `sed` to temporarily modify `pom.xml` during the Docker build:

```dockerfile
RUN sed -i 's/<java.version>25<\/java.version>/<java.version>21<\/java.version>/g' pom.xml && \
    sed -i 's/<maven.compiler.source>25<\/maven.compiler.source>/<maven.compiler.source>21<\/maven.compiler.source>/g' pom.xml && \
    sed -i 's/<maven.compiler.target>25<\/maven.compiler.target>/<maven.compiler.target>21<\/maven.compiler.target>/g' pom.xml && \
    sed -i 's/<source>25<\/source>/<source>21<\/source>/g' pom.xml && \
    sed -i 's/<target>25<\/target>/<target>21<\/target>/g' pom.xml && \
    sed -i 's/<release>25<\/release>/<release>21<\/release>/g' pom.xml
```

This ensures:
- ✅ Build uses Java 21 (available in Docker images)
- ✅ Source code compiles successfully
- ✅ JAR file is created
- ✅ Runtime uses JRE 21

**Note**: The original `pom.xml` is not modified - only the copy inside the Docker build context.

---

## 🎯 Production vs Local Development

### Production (`Dockerfile`)
- Uses Java 25 (when images are available)
- Matches production requirements
- Optimized for AWS Graviton2 (ARM64)

### Local Development (`Dockerfile.local`)
- Uses Java 21 (stable and available)
- Works on all platforms (x86_64 and ARM64)
- Easier to build and test locally

---

## ✅ Verification

After the fix:

```bash
# Build succeeds
docker-compose build backend
# ✅ BUILD SUCCESS

# Services start
docker-compose up -d
# ✅ Services started

# Health check works
curl http://localhost:8080/actuator/health
# ✅ {"status":"UP"}
```

---

## 📝 Notes

- **Java 25**: The project still targets Java 25 for production
- **Local Development**: Uses Java 21 for compatibility
- **No Code Changes**: The source code doesn't need to change
- **Temporary Modification**: `pom.xml` is only modified during Docker build, not in the repository

---

## 🔄 Future Updates

When Java 25 Docker images become widely available:

1. Update `Dockerfile.local` to use Java 25:
   ```dockerfile
   FROM --platform=linux/arm64 maven:3.9-eclipse-temurin-25 AS build
   ```

2. Remove the `sed` commands that modify `pom.xml`

3. Test the build

---

**Status**: ✅ **FIXED** - Docker build now works for local development

