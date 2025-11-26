#!/bin/bash
# Script to set Java 21 for Maven builds
# Usage: source SET_JAVA_21.sh

echo "Setting Java 21 for Maven..."

# Try to find Java 21
JAVA_21_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null)

if [ -z "$JAVA_21_HOME" ]; then
    echo "❌ Java 21 not found!"
    echo ""
    echo "Please install Java 21:"
    echo "  macOS: brew install openjdk@21"
    echo "  Or download from: https://adoptium.net/temurin/releases/?version=21"
    echo ""
    echo "Available Java versions:"
    /usr/libexec/java_home -V 2>&1 | head -10
    exit 1
fi

export JAVA_HOME="$JAVA_21_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

echo "✅ Java 21 set successfully"
echo "   JAVA_HOME: $JAVA_HOME"
java -version 2>&1 | head -3
echo ""
echo "You can now run: mvn clean install"

