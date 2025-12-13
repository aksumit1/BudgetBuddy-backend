# Docker Maven Integration

This project includes automatic Docker container management during Maven builds, similar to the CI/CD pipeline.

## How It Works

The `exec-maven-plugin` with wrapper scripts automatically:
1. **Starts** Docker containers (LocalStack and Redis) before tests
2. **Waits** for services to be healthy before proceeding
3. **Stops** and **cleans up** containers after tests complete

Uses wrapper scripts that work with both:
- Docker Compose V1 (`docker-compose`)
- Docker Compose V2 (`docker compose`)

## Independence & Resilience

The plugin is designed to work in **all scenarios**, making tests completely independent:

✅ **Containers already running**: Uses existing containers (docker-compose up is idempotent)  
✅ **Containers not running**: Starts containers automatically  
✅ **Containers starting**: Waits for them to become healthy  
✅ **Containers stopped**: Safe to run (docker-compose down won't fail if nothing is running)

This means you can:
- Run `mvn test` with containers already up → Uses existing containers, doesn't stop them
- Run `mvn test` with containers down → Starts containers automatically, stops them after
- Run `mvn test` while containers are starting → Waits for them to be ready
- Run `mvn test` multiple times → Each run is independent

### Cleanup Behavior

Containers are automatically stopped after tests. This is safe because:
- `docker-compose down` won't fail if containers aren't running
- If containers were already running before tests, they'll be stopped (clean state)
- If containers were started by Maven, they'll be stopped (cleanup)

To keep containers running for multiple test runs:

```bash
# Skip Docker management entirely (use existing containers, don't stop them)
mvn test -DskipDocker=true

# Normal behavior: start before tests, stop after tests
mvn test
```

## Usage

### Normal Usage (Docker Auto-Management)

Simply run Maven commands as usual:

```bash
# Run tests with automatic Docker management
mvn test

# Run integration tests with automatic Docker management
mvn verify

# Full build with automatic Docker management
mvn install
```

The plugin will:
- Start `localstack` and `redis` containers before tests
- Wait for services to be healthy (up to 2 minutes for LocalStack, 1 minute for Redis)
- Run your tests
- Stop and remove containers after tests complete

### Skip Docker (Use Existing Containers)

If you already have Docker containers running, you can skip the plugin:

```bash
# Skip Docker startup/cleanup
mvn test -DskipDocker=true
mvn verify -DskipDocker=true
mvn install -DskipDocker=true
```

### Manual Docker Management

If you prefer to manage Docker manually:

```bash
# Start containers manually
docker-compose up -d localstack redis

# Run tests
mvn test -DskipDocker=true

# Stop containers manually
docker-compose down
```

## Lifecycle Integration

The plugin is integrated into the Maven lifecycle:

- **`process-test-classes` phase**: Starts Docker containers
- **`post-integration-test` phase**: Stops Docker containers
- **`verify` phase**: Stops Docker containers (for `mvn verify`)
- **`install` phase**: Stops Docker containers (for `mvn install`)

## Health Checks

The plugin waits for services to be healthy:

- **LocalStack**: Checks `http://localhost:4566/_localstack/health` (up to 2 minutes)
- **Redis**: Executes `redis-cli ping` (up to 1 minute)

## Requirements

- Docker must be installed and running
- Either `docker-compose` (V1) or `docker compose` (V2) must be available
- Ports 4566 (LocalStack) and 6379 (Redis) must be available
- Wrapper scripts in `scripts/` directory handle compatibility automatically

## Troubleshooting

### Docker Not Running

If Docker is not running, the plugin will fail. Start Docker Desktop or Docker daemon first.

### Port Conflicts

If ports 4566 or 6379 are already in use, either:
1. Stop the conflicting services
2. Use `-DskipDocker=true` to use existing containers
3. Modify `docker-compose.yml` to use different ports

### Containers Not Starting

Check Docker logs:
```bash
docker-compose logs localstack
docker-compose logs redis
```

### Cleanup Issues

If containers don't stop properly:
```bash
docker-compose down -v  # Remove volumes too
```

## CI/CD Compatibility

This setup mirrors the CI/CD pipeline behavior:
- GitHub Actions uses service containers (similar to Docker Compose)
- Local development uses Docker Compose via Maven plugin
- Both approaches ensure consistent test environments

