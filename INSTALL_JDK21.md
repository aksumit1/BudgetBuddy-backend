# Install JDK 21 for BudgetBuddy Backend

## Quick Install (macOS)

### Option 1: Homebrew (Recommended)
```bash
brew install openjdk@21
```

### Option 2: Manual Download
1. Visit [Adoptium](https://adoptium.net/temurin/releases/?version=21)
2. Download macOS installer (.pkg)
3. Install the package

## Set JAVA_HOME

### Temporary (Current Session)
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$JAVA_HOME/bin:$PATH
```

### Permanent (Add to ~/.zshrc or ~/.bashrc)
```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.zshrc
source ~/.zshrc
```

## Verify Installation

```bash
java -version
# Should show: openjdk version "21.x.x"
```

## Build with JDK 21

```bash
cd BudgetBuddy-Backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean install
```

## Troubleshooting

### Java 21 Not Found
```bash
# Check available versions
/usr/libexec/java_home -V

# If Java 21 is installed but not found, try:
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
```

### Maven Still Using Wrong Java
```bash
# Check Maven Java version
mvn -version

# If wrong, ensure JAVA_HOME is set correctly
echo $JAVA_HOME
```

