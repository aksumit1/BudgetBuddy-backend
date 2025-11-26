#!/bin/bash

# Generate Self-Signed SSL Certificate for Local Development
# This script creates a PKCS12 keystore for Spring Boot SSL configuration

set -e

KEYSTORE_DIR="src/main/resources"
KEYSTORE_FILE="${KEYSTORE_DIR}/keystore.p12"
KEYSTORE_PASSWORD="changeit"
KEY_ALIAS="budgetbuddy"
VALIDITY_DAYS=365

echo "Generating self-signed SSL certificate for local development..."

# Create resources directory if it doesn't exist
mkdir -p "${KEYSTORE_DIR}"

# Generate keystore with self-signed certificate
keytool -genkeypair \
  -alias "${KEY_ALIAS}" \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore "${KEYSTORE_FILE}" \
  -validity "${VALIDITY_DAYS}" \
  -storepass "${KEYSTORE_PASSWORD}" \
  -keypass "${KEYSTORE_PASSWORD}" \
  -dname "CN=localhost, OU=Development, O=BudgetBuddy, L=San Francisco, ST=CA, C=US" \
  -ext "SAN=DNS:localhost,DNS:*.localhost,IP:127.0.0.1"

echo "✅ SSL certificate generated successfully!"
echo ""
echo "Keystore location: ${KEYSTORE_FILE}"
echo "Keystore password: ${KEYSTORE_PASSWORD}"
echo "Key alias: ${KEY_ALIAS}"
echo ""
echo "To enable HTTPS in local development, set the following environment variables:"
echo "  SERVER_SSL_ENABLED=true"
echo "  SERVER_SSL_KEY_STORE=classpath:keystore.p12"
echo "  SERVER_SSL_KEY_STORE_PASSWORD=${KEYSTORE_PASSWORD}"
echo "  SERVER_SSL_KEY_ALIAS=${KEY_ALIAS}"
echo ""
echo "Or add to docker-compose.yml:"
echo "  SERVER_SSL_ENABLED: 'true'"
echo ""
echo "⚠️  WARNING: This is a self-signed certificate for local development only!"
echo "   Your browser will show a security warning. Accept it to proceed."
echo "   For production, use a certificate from a trusted CA (e.g., Let's Encrypt)."

