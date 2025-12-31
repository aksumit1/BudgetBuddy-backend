#!/bin/bash
#
# Install Git hooks for BudgetBuddy Backend
# This script copies hooks from .githooks/ to .git/hooks/
#

set -e

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
cd "$PROJECT_ROOT"

echo "🔧 Installing Git hooks for BudgetBuddy Backend..."
echo ""

# Check if .githooks directory exists
if [ ! -d ".githooks" ]; then
    echo "❌ .githooks directory not found!"
    exit 1
fi

# Create .git/hooks directory if it doesn't exist
mkdir -p .git/hooks

# Copy all hooks from .githooks/ to .git/hooks/
for hook in .githooks/*; do
    if [ -f "$hook" ]; then
        hook_name=$(basename "$hook")
        cp "$hook" ".git/hooks/$hook_name"
        chmod +x ".git/hooks/$hook_name"
        echo "✅ Installed: $hook_name"
    fi
done

echo ""
echo "✅ Git hooks installed successfully!"
echo ""
echo "💡 To skip hooks: git push --no-verify"
echo "💡 To uninstall: rm .git/hooks/pre-push"

