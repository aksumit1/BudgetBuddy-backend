# Quick Fix: Install and Use JDK 21

## Problem
Tests are failing because the system is using Java 25, which has compatibility issues with Mockito.

## Solution: Install JDK 21

### Step 1: Install JDK 21
```bash
brew install openjdk@21
```

### Step 2: Set JAVA_HOME for this session
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$JAVA_HOME/bin:$PATH
```

### Step 3: Verify
```bash
java -version
# Should show: openjdk version "21.x.x"
```

### Step 4: Run tests
```bash
cd BudgetBuddy-Backend
mvn clean test
```

## Alternative: Use Maven Toolchains

If you can't install JDK 21 globally, you can configure Maven to use it via toolchains.

### Create ~/.m2/toolchains.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
    </provides>
    <configuration>
      <jdkHome>/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

Then update `pom.xml` to use toolchains (requires Maven toolchains plugin).

## Quick Check Commands

```bash
# Check current Java version
java -version

# Check available Java versions
/usr/libexec/java_home -V

# Check if JDK 21 is installed
/usr/libexec/java_home -v 21 2>&1

# If JDK 21 is installed, set it:
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

