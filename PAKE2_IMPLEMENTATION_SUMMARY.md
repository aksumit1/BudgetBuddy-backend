# PAKE2 Challenge-Response Implementation

## Protocol Flow

### Registration
1. Client → POST /api/auth/register/challenge { "email": "user@example.com" }
2. Server → { "challenge": "base64-nonce", "expiresAt": "2024-01-01T00:05:00Z" }
3. Client → POST /api/auth/register { "email": "...", "passwordHash": "PBKDF2(password+challenge)", "challenge": "..." }
4. Server verifies challenge, hashes with server salt, stores user

### Login
1. Client → POST /api/auth/login/challenge { "email": "user@example.com" }
2. Server → { "challenge": "base64-nonce", "expiresAt": "2024-01-01T00:05:00Z" }
3. Client → POST /api/auth/login { "email": "...", "passwordHash": "PBKDF2(password+challenge)", "challenge": "..." }
4. Server verifies challenge, verifies password hash

## Implementation Order
1. Backend ChallengeService (generate/store/verify nonces)
2. Backend challenge endpoints
3. Backend Autates
4. iOS ChallengeService
5. iOS AuthService updates
6. Remove legacy code
7. Update tests
