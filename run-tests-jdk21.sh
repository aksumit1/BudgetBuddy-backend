#!/bin/bash
# Script to run tests with JDK 21

set -e

echo "🔧 Setting up JDK 21..."

# Set JAVA_HOME to JDK 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$JAVA_HOME/bin:$PATH

echo "✅ Using Java:"
java -version

echo ""
echo "🧪 Running tests..."
cd "$(dirname "$0")"
mvn clean test

