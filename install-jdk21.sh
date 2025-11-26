#!/bin/bash
# Script to install JDK 21 and set it up for BudgetBuddy Backend

set -e

echo "🔍 Checking for JDK 21..."

# Check if JDK 21 is already installed
if JAVA_21_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null); then
    echo "✅ JDK 21 is already installed at: $JAVA_21_HOME"
    echo ""
    echo "To use it, run:"
    echo "  export JAVA_HOME=$JAVA_21_HOME"
    echo "  export PATH=\$JAVA_HOME/bin:\$PATH"
    echo "  cd BudgetBuddy-Backend && mvn clean test"
    exit 0
fi

echo "❌ JDK 21 not found. Installing..."

# Check if Homebrew is installed
if ! command -v brew &> /dev/null; then
    echo "❌ Homebrew is not installed."
    echo "Please install Homebrew first: https://brew.sh"
    echo "Or download JDK 21 manually from: https://adoptium.net/temurin/releases/?version=21"
    exit 1
fi

# Install JDK 21
echo "📦 Installing JDK 21 via Homebrew..."
brew install openjdk@21

# Set up symlink (if needed)
if [ ! -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk" ]; then
    echo "🔗 Creating symlink..."
    sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/temurin-21.jdk
fi

# Verify installation
if JAVA_21_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null); then
    echo "✅ JDK 21 installed successfully!"
    echo ""
    echo "📍 Location: $JAVA_21_HOME"
    echo ""
    echo "To use JDK 21, run:"
    echo "  export JAVA_HOME=$JAVA_21_HOME"
    echo "  export PATH=\$JAVA_HOME/bin:\$PATH"
    echo "  cd BudgetBuddy-Backend && mvn clean test"
    echo ""
    echo "Or add to your ~/.zshrc for permanent setup:"
    echo "  echo 'export JAVA_HOME=\$(/usr/libexec/java_home -v 21)' >> ~/.zshrc"
    echo "  echo 'export PATH=\$JAVA_HOME/bin:\$PATH' >> ~/.zshrc"
else
    echo "❌ Installation completed but JDK 21 not found."
    echo "Please try: brew install openjdk@21"
    exit 1
fi

