# BudgetBuddy — Architecture Review

**Status:** snapshot review, ranked by impact × inverse blast-radius
**Date:** 2026-05-23
**Out of scope:** insights subsystem (recently audited end-to-end), PDF importer
(Phase-1 split landed), anomaly feedback loop, prediction-service split, magic-number
externalization, InsightsContext, transaction-action plumbing.

## Top 15 issues

### Security & compliance (highest priority)

| # | Issue                                                                   | Impact | Blast |
|---|--------------------------------------------------------------------------|--------|-------|
| 1 | **PII (emails) logged at INFO in auth flows**                            | HIGH   | large |
| 2 | **Default secrets (`JWT_SECRET`, `ENCRYPTION_KEY`) in `application.yml`** | HIGH   | large |
| 3 | **IDOR risk: account fetch doesn't validate `userId` field post-fetch**  | HIGH   | large |
| 11 | **Mutation endpoints lack per-user rate limiting** (DoS surface)       | MED    | med   |

### Reliability & deployment

| # | Issue                                                                   | Impact | Blast |
|---|--------------------------------------------------------------------------|--------|-------|
| 4 | **`DynamoDBTableManager` @PostConstruct crashes startup on any table failure** | HIGH   | large |
| 6 | **`DistributedLock` silently falls back to local locks when Redis down** | MED    | med   |
| 5 | **Hardcoded 50K pagination ceiling silently truncates power users**     | MED    | small |

### Observability & operations

| # | Issue                                                                   | Impact | Blast |
|---|--------------------------------------------------------------------------|--------|-------|
| 8 | **No structured logging for transaction-mutation audit trail**          | MED    | med   |
| 2 | **118× `@Autowired(required=false)` field injection silently degrades** | MED    | med   |
| 12 | **AWS SDK exceptions leak as 500s instead of mapping to 4xx**          | MED    | small |
| 7 | **LocalStack tests fail in CI when Docker unavailable** (no skip)       | MED    | small |

### Performance & data integrity

| # | Issue                                                                   | Impact | Blast |
|---|--------------------------------------------------------------------------|--------|-------|
| 14 | **Transaction cache eviction is global, not per-user**                 | MED    | med   |
| 13 | **N+1 query risk in `WeeklyDigestService`**                            | MED    | small |
| 15 | **Plaid IDs not normalized → dedup failures on case differences**      | MED    | small |
| 9 | **Inconsistent API response shape: bare entity vs. wrapped DTO**        | MED    | small |

---

## Issue detail (prioritized)

### #1 — PII (emails) logged at INFO in auth flows
**Where:** `AuthController.java:183, 248, 322`; `AuthService.java:166, 243`
**Issue:** User emails written to logs at INFO level — visible to ops, in CloudWatch
retention, and a target if logs leak. Violates GDPR/CCPA data minimization.
**Fix:** Use a `maskEmail()` helper (already partially present in
`PrivacyRedaction`) at every email log site; restrict raw emails to DEBUG. Add a
checkstyle rule `LogPiiPattern` to fail builds that introduce `user.getEmail()` in
a `LOGGER.info` argument.
**Effort:** M · **Fix risk:** LOW (additive, no behavior change).

### #2 — Default secrets in `application.yml`
**Where:** `src/main/resources/application.yml:125, 203`
**Issue:** `JWT_SECRET:your-256-bit-secret-key-change-in-production` and similar
defaults — checked into git, weak, and used silently if env vars are missing.
**Fix:** Change defaults to empty (`${JWT_SECRET:}`). On startup, validate every
secret is non-empty; fail-fast with a clear message naming the missing env var.
Add a deployment-time check.
**Effort:** S · **Fix risk:** LOW (startup validation only). **Quick win.**

### #3 — IDOR risk on account/budget/goal fetches
**Where:** `AccountController.java:106-109` (TODO comment); pattern repeated in
budget / goal / subscription endpoints.
**Issue:** Endpoints filter by `userId` but don't validate the returned rows'
`userId` field. A corrupted row (null/wrong `userId`) could leak across users.
**Fix:** Add post-fetch verification inside each `findByUserId` repository method:
filter mismatches, log unexpected ones loudly. Add cross-user integration tests
that attempt access and expect 404. Centralize the check in a `UserScopedRepository`
mixin so future repositories inherit it.
**Effort:** M · **Fix risk:** MED (touches every entity repo).

### #4 — `@PostConstruct` table init crashes startup
**Where:** `DynamoDBTableManager.java:96-120`
**Issue:** All 20+ tables created at startup in a single try block. A GSI conflict
on one table = whole backend fails to boot. No per-table isolation.
**Fix:** Per-table try/catch; categorize tables as `CRITICAL` (Users, Transactions,
Accounts) vs `OPTIONAL` (NotFoundTracking, AuditLogs). Critical failures fail-fast;
optional failures log WARN and continue. Wire to readiness probe.
**Effort:** M · **Fix risk:** MED.

### #5 — 50K pagination ceiling silently truncates
**Where:** `TransactionRepository.java:79` (`MAX_FALLBACK_FETCH = 50_000`)
**Issue:** Hardcoded in-memory fallback ceiling during GSI outage. Power users
silently lose rows; no alert when the cap is hit.
**Fix:** Move to config (`app.dynamodb.max-fallback-fetch`). On hit, throw
`AppException(RESOURCE_LIMIT_EXCEEDED)` and emit a CloudWatch metric.
**Effort:** M · **Fix risk:** LOW.

### #6 — Distributed-lock silent fallback
**Where:** `DistributedLock.java:60-71`
**Issue:** Redis down → local JVM lock → no cross-replica safety. Plaid sync /
batch import can produce duplicates across pods. Only logged once at startup.
**Fix:** Emit WARN on every acquisition while Redis is down. Add a strict mode
(`app.lock.require-redis=true`) for safety-critical call sites that throws when
Redis is unreachable.
**Effort:** M · **Fix risk:** MED.

### #7 — LocalStack tests don't skip when Docker is absent
**Where:** `LocalStackTestBootstrap.java`, `DynamoDBTestConfiguration.java`
**Issue:** Tests requiring Testcontainers fall through to a hardcoded endpoint and
fail unhelpfully when Docker isn't running. CI without Docker breaks here.
**Fix:** Add `@Tag("requires-docker")` to all such tests; wire JUnit 5 with
`Assumptions.assumeTrue(dockerAvailable)`. Surface a clear log line on skip.
**Effort:** S · **Fix risk:** LOW. **Quick win.**

### #8 — No structured logging for mutation audit trail
**Where:** `TransactionService.java:2115, 2230` and similar
**Issue:** String-interpolated INFO logs for transaction mutations. No structured
fields → audits require regex on text.
**Fix:** Use SLF4J MDC to carry `correlationId`, `userId`, `transactionId`, action,
old/new diff. Use the existing `AuditLogService` instead of inline logging.
**Effort:** M · **Fix risk:** LOW.

### #9 — API response shape inconsistency
**Where:** `TransactionController.java:363-385` (bare entity) vs
`AnalyticsController.java` (wrapped DTO).
**Issue:** iOS client must handle both. Field naming drifts.
**Fix:** Define `ApiResponse<T>` envelope with `status`, `data`, `error`,
`correlationId`. Migrate endpoints incrementally; keep both shapes accepted on the
client during the transition.
**Effort:** L · **Fix risk:** MED (client-visible).

### #10 — `@Autowired(required=false)` silent degradation (118 instances)
**Where:** repo-wide grep
**Issue:** Same pattern I fixed for the insights subsystem, but proliferated.
**Fix:** Sweep: replace with constructor injection or explicit `Optional<>`. For
remaining `required=false`, add a `@PostConstruct` warn-when-null hook (now an
established pattern in `TransactionAnomalyService`).
**Effort:** L · **Fix risk:** LOW (mechanical).

### #11 — Rate limiting not wired to mutation endpoints
**Where:** `application.yml:155-180` + `TransactionController.java:387`
**Issue:** Global rate limits set to 1M/hour — effectively off. No per-user
per-minute caps. Batch import accepts 100 files / call without per-user total caps.
**Fix:** `@RateLimited` annotation per endpoint with sensible defaults (100
mutations/min/user). Token bucket keyed on userId, not IP. 429 + Retry-After.
**Effort:** M · **Fix risk:** MED.

### #12 — Exception translation incomplete
**Where:** `EnhancedGlobalExceptionHandler.java` + controllers
**Issue:** AWS SDK exceptions (e.g. `ConditionalCheckFailedException`) sometimes
surface as 500. Should map to 409.
**Fix:** Add `ExceptionTranslator` that maps SDK exceptions to `AppException` with
correct HTTP codes; register in the global handler.
**Effort:** M · **Fix risk:** LOW.

### #13 — N+1 risk in `WeeklyDigestService`
**Where:** `WeeklyDigestService.java:149, 191`
**Issue:** Loop over budgets/goals where inner loop hits the repo per item.
**Fix:** Batch-fetch related entities upfront; lookup in a Map inside the loop.
**Effort:** M · **Fix risk:** LOW.

### #14 — Global transaction-cache eviction
**Where:** `TransactionRepository.java:105` (`@CacheEvict(allEntries=true)`)
**Issue:** One user's update clears the cache for everyone.
**Fix:** Partition keys by `userId`; targeted eviction. Document TTL.
**Effort:** M · **Fix risk:** MED.

### #15 — Plaid IDs not normalized
**Where:** `TransactionRepository.java:144`
**Issue:** `transactionId` is lowercased via `IdGenerator.normalizeUUID()` but
`plaidTransactionId` is stored as-is — case-sensitive lookups break dedup.
**Fix:** Normalize on store + on query; backfill if needed.
**Effort:** S · **Fix risk:** LOW.

---

## Quick wins (HIGH-impact, ≤30 min effort each)

1. **Remove default secrets from `application.yml`** + add startup validation (#2)
2. **Mask emails in INFO-level logs** (#1) — replace at the ~5 call sites in `AuthController` / `AuthService`
3. **Tag LocalStack tests `@Tag("requires-docker")`** + add skip-when-absent (#7)
4. **Normalize Plaid IDs** at write + query time (#15)

These four together close two HIGH-impact security gaps and unblock CI for
non-Docker contributors. Estimated total: 90 minutes.

---

## What this review didn't cover

- Detailed iOS-app review (separate exercise; relies on Xcode project context)
- DDB schema/GSI design review (deserves its own deep dive — partition-key hot-spot
  analysis needs production access logs)
- Multi-tenant data isolation (different angle from IDOR — covers backups, restores,
  cross-account leakage in shared infrastructure)

---

## Recommended sequence

1. **Now (this session):** ship the 4 quick wins (#2, #1, #7, #15) — security + DX.
2. **Next sprint:** #3 (IDOR hardening) + #4 (PostConstruct isolation) — both are
   reliability-critical and the fixes are well-scoped.
3. **Following sprint:** #6 (distributed-lock strict mode) + #11 (rate limiting) —
   both DoS / data-consistency protections.
4. **Backlog:** the rest, prioritized by team capacity. #10 (field injection sweep)
   is a good "background cleanup" effort that can run in parallel with feature work.
