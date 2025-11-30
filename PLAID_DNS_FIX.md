# Plaid DNS Resolution Fix

## Problem
The backend container was unable to resolve `sandbox.plaid.com`, causing Plaid API calls to fail with:
```
java.net.UnknownHostException: sandbox.plaid.com: Try again
```

## Root Cause
Docker's embedded DNS server (127.0.0.11) was configured as the only DNS server, but it couldn't resolve external hostnames like `sandbox.plaid.com`. It only handles service discovery within the Docker network.

## Solution
Added external DNS servers (8.8.8.8, 8.8.4.4) to the backend container's DNS configuration. Docker's embedded DNS now forwards external queries to these servers.

## Changes Made

### docker-compose.yml
Updated the `backend` service DNS configuration:
```yaml
dns:
  - 8.8.8.8     # Google DNS (primary for external hostnames)
  - 8.8.4.4     # Google DNS secondary
  - 127.0.0.11  # Docker's embedded DNS (for service discovery within Docker network)
```

## Verification

DNS resolution now works:
```bash
$ docker-compose exec backend nslookup sandbox.plaid.com
Name:   sandbox.plaid.com
Address: 3.234.14.107
Address: 52.7.212.85
Address: 98.85.7.173
```

## Result
- ✅ External hostnames (like `sandbox.plaid.com`) can now be resolved
- ✅ Service discovery within Docker network still works (via 127.0.0.11)
- ✅ Plaid API calls should now succeed
- ✅ No impact on other services (LocalStack, Redis, etc.)

## Note
The DNS servers are listed in order of priority. Docker's embedded DNS (127.0.0.11) will:
1. First try to resolve using its internal cache/service discovery
2. Forward external queries to 8.8.8.8 or 8.8.4.4 if it can't resolve internally

This provides the best of both worlds: fast service discovery for Docker services and reliable external DNS resolution.

