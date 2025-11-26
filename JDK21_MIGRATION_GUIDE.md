# JDK 21 Migration Guide

## Status
✅ **pom.xml updated to JDK 21**
⚠️ **System still running JDK 25** - Java 21 needs to be installed

## Changes Made

### 1. pom.xml Updates
- ✅ Changed `<java.version>` from `25` to `21`
- ✅ Changed `<maven.compiler.source>` from `25` to `21`
- ✅ Changed `<maven.compiler.target>` from `25` to `21`
- ✅ Updated compiler plugin configuration
- ✅ Removed Java 25-specific ASM overrides
- ✅ Enabled JaCoCo code coverage
- ✅ Removed test failure ignore flags

### 2. Test Enabling
- ✅ Removed `@Disabled` annotations from all test files:
  - `PlaidSyncIntegrationTest.java`
  - `PlaidServiceTest.java`
  - `PlaidControllerTest.java`
  - `AmountValidatorTest.java`
  - `PasswordStrengthValidatorTest.java`
  - `BudgetServiceTest.java`
  - `GoalControllerTest.java`
  - `ChaosTest.java`
  - `PlaidControllerIntegrationTest.java`

### 3. Code Coverage
- ✅ Enabled JaCoCo with 50% minimum coverage requirement
- ✅ Configured for unit and integration tests

## Installation Required

### Install Java 21

**macOS (Homebrew)**:
```bash
brew install openjdk@21
```

**macOS (Manual)**:
1. Download from [Adoptium](https://adoptium.net/temurin/releases/?version=21)
2. Install the .pkg file
3. Set JAVA_HOME:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

**Linux (apt)**:
```bash
sudo apt-get update
sudo apt-get install openjdk-21-jdk
```

**Linux (yum)**:
```bash
sudo yum install java-21-openjdk-devel
```

### Verify Installation
```bash
java -version
# Should show: openjdk version "21.x.x"
```

### Set JAVA_HOME
```bash
# macOS
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Linux
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

## Build Commands

### Clean Build
```bash
mvn clean install
```

### Run Tests
```bash
mvn test
```

### Run with Coverage
```bash
mvn clean test jacoco:report
# View report: target/site/jacoco/index.html
```

## Known Issues

### Mockito with Java 25
If tests fail with Mockito errors when running on Java 25:
- **Error**: `Mockito cannot mock this class`
- **Solution**: Install and use Java 21 as specified above

### Spring Boot Context Loading
If integration tests fail with context loading errors:
- **Error**: `Spring Boot context fails to load`
- **Solution**: Ensure Java 21 is being used (check `java -version`)

## Next Steps

1. **Install Java 21** (see above)
2. **Set JAVA_HOME** to Java 21
3. **Run build**: `mvn clean install`
4. **Run tests**: `mvn test`
5. **Check coverage**: `mvn jacoco:report`

## Benefits of JDK 21

- ✅ Full Spring Boot 3.4.1 compatibility
- ✅ Mockito/ByteBuddy mocking works correctly
- ✅ JaCoCo code coverage works
- ✅ All tests can run without workarounds
- ✅ Better performance and stability

