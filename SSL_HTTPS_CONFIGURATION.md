# SSL/HTTPS Configuration Guide

## Overview

This guide explains how to configure SSL/HTTPS for the BudgetBuddy backend, both for local development and production.

---

## Local Development (Self-Signed Certificate)

### 1. Generate Self-Signed Certificate

Run the certificate generation script:

```bash
cd BudgetBuddy-Backend
./scripts/generate-ssl-certificate.sh
```

This will create a `keystore.p12` file in `src/main/resources/` with:
- **Keystore password**: `changeit`
- **Key alias**: `budgetbuddy`
- **Validity**: 365 days
- **Subject**: `CN=localhost`

### 2. Enable SSL in Local Development

#### Option A: Environment Variables

```bash
export SERVER_SSL_ENABLED=true
export SERVER_SSL_KEY_STORE=classpath:keystore.p12
export SERVER_SSL_KEY_STORE_PASSWORD=changeit
export SERVER_SSL_KEY_ALIAS=budgetbuddy
```

#### Option B: Docker Compose

Add to `docker-compose.yml`:

```yaml
environment:
  SERVER_SSL_ENABLED: 'true'
```

### 3. Access HTTPS Endpoint

Once enabled, access the application via:
- **HTTPS**: `https://localhost:8080`
- **HTTP**: `http://localhost:8080` (still available)

**Note**: Your browser will show a security warning for the self-signed certificate. This is expected. Click "Advanced" → "Proceed to localhost" to continue.

---

## Production (Trusted Certificate)

### Option 1: AWS Certificate Manager (ACM)

For AWS deployments, use ACM certificates:

1. **Request Certificate in ACM**:
   ```bash
   aws acm request-certificate \
     --domain-name api.budgetbuddy.com \
     --validation-method DNS \
     --region us-east-1
   ```

2. **Configure ALB to Use ACM Certificate**:
   - The ALB listener uses the ACM certificate
   - Backend runs on HTTP (8080)
   - ALB terminates SSL/TLS

3. **Application Configuration**:
   ```yaml
   server:
     ssl:
       enabled: false  # ALB handles SSL termination
   ```

### Option 2: Let's Encrypt (Self-Managed)

1. **Install Certbot**:
   ```bash
   sudo apt-get update
   sudo apt-get install certbot
   ```

2. **Obtain Certificate**:
   ```bash
   sudo certbot certonly --standalone -d api.budgetbuddy.com
   ```

3. **Convert to PKCS12**:
   ```bash
   openssl pkcs12 -export \
     -in /etc/letsencrypt/live/api.budgetbuddy.com/fullchain.pem \
     -inkey /etc/letsencrypt/live/api.budgetbuddy.com/privkey.pem \
     -out keystore.p12 \
     -name budgetbuddy \
     -password pass:YOUR_PASSWORD
   ```

4. **Configure Application**:
   ```yaml
   server:
     ssl:
       enabled: true
       key-store: file:/path/to/keystore.p12
       key-store-password: YOUR_PASSWORD
       key-alias: budgetbuddy
   ```

---

## Configuration Properties

### application.yml

```yaml
server:
  ssl:
    enabled: ${SERVER_SSL_ENABLED:false}
    key-store: ${SERVER_SSL_KEY_STORE:classpath:keystore.p12}
    key-store-password: ${SERVER_SSL_KEY_STORE_PASSWORD:changeit}
    key-store-type: ${SERVER_SSL_KEY_STORE_TYPE:PKCS12}
    key-alias: ${SERVER_SSL_KEY_ALIAS:budgetbuddy}
    protocol: TLS
    enabled-protocols: ${SERVER_SSL_ENABLED_PROTOCOLS:TLSv1.2,TLSv1.3}
    ciphers: ${SERVER_SSL_CIPHERS:TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,...}
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_SSL_ENABLED` | `false` | Enable/disable SSL |
| `SERVER_SSL_KEY_STORE` | `classpath:keystore.p12` | Keystore location |
| `SERVER_SSL_KEY_STORE_PASSWORD` | `changeit` | Keystore password |
| `SERVER_SSL_KEY_STORE_TYPE` | `PKCS12` | Keystore type |
| `SERVER_SSL_KEY_ALIAS` | `budgetbuddy` | Key alias |
| `SERVER_SSL_ENABLED_PROTOCOLS` | `TLSv1.2,TLSv1.3` | Enabled TLS protocols |
| `SERVER_SSL_CIPHERS` | (see config) | Enabled cipher suites |

---

## Security Best Practices

### 1. **TLS Protocol Versions**
- ✅ **Use**: TLS 1.2 and TLS 1.3
- ❌ **Avoid**: TLS 1.0, TLS 1.1, SSL 3.0

### 2. **Cipher Suites**
- ✅ **Use**: Strong cipher suites (AES-GCM, ECDHE)
- ❌ **Avoid**: Weak ciphers (RC4, DES, MD5)

### 3. **Certificate Management**
- ✅ **Production**: Use certificates from trusted CAs (Let's Encrypt, ACM)
- ✅ **Local Dev**: Self-signed certificates are acceptable
- ❌ **Never**: Commit production certificates to version control

### 4. **Keystore Security**
- ✅ **Store passwords**: In AWS Secrets Manager or environment variables
- ✅ **File permissions**: Restrict keystore file permissions (600)
- ❌ **Never**: Hardcode passwords in code

---

## Troubleshooting

### Issue: "PKCS12 keystore not found"

**Solution**: 
1. Run `./scripts/generate-ssl-certificate.sh`
2. Verify `src/main/resources/keystore.p12` exists
3. Check file permissions

### Issue: "Invalid keystore format"

**Solution**:
1. Verify keystore type is PKCS12
2. Check keystore password is correct
3. Regenerate keystore if corrupted

### Issue: "Certificate not trusted" (Browser Warning)

**Solution**:
- **Local Dev**: This is expected for self-signed certificates. Accept the warning.
- **Production**: Use a certificate from a trusted CA (Let's Encrypt, ACM)

### Issue: "Connection refused" on HTTPS

**Solution**:
1. Verify `SERVER_SSL_ENABLED=true`
2. Check keystore file exists and is readable
3. Verify port 8080 is not blocked by firewall
4. Check application logs for SSL initialization errors

---

## Testing SSL Configuration

### 1. **Check SSL Certificate**

```bash
# Using OpenSSL
openssl s_client -connect localhost:8080 -showcerts

# Using curl
curl -v https://localhost:8080/actuator/health --insecure
```

### 2. **Test TLS Protocols**

```bash
# Test TLS 1.2
openssl s_client -connect localhost:8080 -tls1_2

# Test TLS 1.3
openssl s_client -connect localhost:8080 -tls1_3
```

### 3. **Verify Cipher Suites**

```bash
openssl s_client -connect localhost:8080 -cipher 'ECDHE-RSA-AES256-GCM-SHA384'
```

---

## Production Deployment

### AWS ECS/ALB Setup

1. **ALB Configuration**:
   - Use ACM certificate for HTTPS listener (port 443)
   - Configure HTTP → HTTPS redirect
   - Backend runs on HTTP (8080) in private subnet

2. **Security Groups**:
   - ALB: Allow inbound 443 (HTTPS) from internet
   - ECS Tasks: Allow inbound 8080 from ALB only

3. **Application Configuration**:
   ```yaml
   server:
     ssl:
       enabled: false  # ALB terminates SSL
     port: 8080
   ```

---

## Summary

- ✅ **Local Dev**: Use self-signed certificate (script provided)
- ✅ **Production**: Use ACM or Let's Encrypt certificates
- ✅ **Security**: TLS 1.2+ only, strong cipher suites
- ✅ **Best Practice**: ALB terminates SSL in production

**Status**: SSL/HTTPS support is fully configured and ready to use!

