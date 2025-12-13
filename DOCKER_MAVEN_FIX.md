# Docker Maven Plugin Warnings - Resolution

## Issue
Warnings about `dockerComposeCommand` parameter being unknown for `docker-compose-maven-plugin`:
```
[WARNING] Parameter 'dockerComposeCommand' is unknown for plugin 'docker-compose-maven-plugin:4.0.0:up (start-docker-services)'
```

## Root Cause
The `docker-compose-maven-plugin` was replaced with `exec-maven-plugin` using wrapper scripts, but Maven may have cached the old plugin configuration.

## Solution

### 1. Clear Maven Cache
```bash
# Clear project build cache
mvn clean

# Clear Maven local repository cache for the plugin (optional)
rm -rf ~/.m2/repository/com/dkanejs/maven/plugins/docker-compose-maven-plugin
```

### 2. Verify Plugin Removal
The `docker-compose-maven-plugin` has been completely removed from `pom.xml`. The current configuration uses:
- `exec-maven-plugin` with wrapper scripts
- `scripts/docker-compose-wrapper.sh` - handles both docker-compose V1 and V2
- `scripts/wait-for-services.sh` - waits for services to be healthy

### 3. Rebuild
```bash
mvn clean validate
mvn test
```

## Current Configuration

The project now uses `exec-maven-plugin` instead of `docker-compose-maven-plugin`:
- **More flexible**: Works with both docker-compose V1 and V2
- **No PATH issues**: Wrapper scripts handle command detection
- **Same functionality**: Automatic Docker management during builds

## If Warnings Persist

If you still see warnings after clearing cache:
1. **Restart your IDE** - IDEs often cache Maven configurations
2. **Check for parent POMs** - Verify no parent POM defines the plugin
3. **Check Maven settings** - Verify `~/.m2/settings.xml` doesn't inject the plugin
4. **Full clean rebuild**: `mvn clean install -U` (forces update of dependencies)

The warnings are harmless but indicate Maven is trying to use a plugin that no longer exists in the configuration.

