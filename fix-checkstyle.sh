#!/bin/bash
# Script to fix common Checkstyle violations (except Javadoc)

cd "$(dirname "$0")"

# Fix FinalParameters: Add final to method parameters
find src -name "*.java" -type f | while read file; do
    # Fix setter methods: public void setXxx(Type param) -> public void setXxx(final Type param)
    sed -i '' 's/\(public void set\w\+\)(\([^)]*\))/\1(\2)/g' "$file"
    sed -i '' 's/\(public void set\w\+\)(\([^,)]\+\)\([^)]*\))/\1(final \2\3)/g' "$file"
    
    # Fix constructor parameters
    sed -i '' 's/\(public \w\+\)(\([^)]*\))/\1(\2)/g' "$file"
    sed -i '' 's/\(public \w\+\)(\([^,)]\+\)\([^)]*\))/\1(final \2\3)/g' "$file"
done

echo "Checkstyle fixes applied"

