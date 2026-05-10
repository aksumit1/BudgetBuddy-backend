package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.benchmark.BenchmarkAggregationService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Community benchmarks endpoint. Returns de-identified percentile bands by category for the
 * caller's bucket (income tier + household size).
 *
 * <p>Cached for an hour via both HTTP Cache-Control headers (client-side) and Caffeine server-side
 * ({@link #benchmarksFor}). Benchmarks change only on the daily aggregation run — no reason to hit
 * DynamoDB on every client open.
 */
@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarksController {

    /** Whitelist of accepted income-tier inputs. Any other value 400s. */
    private static final Set<String> ALLOWED_INCOME_TIERS =
            Set.of("inc-0-50k", "inc-50-75k", "inc-75-150k", "inc-150k-plus");

    private final UserService userService;
    private final BenchmarkAggregationService benchmarks;

    public BenchmarksController(
            final UserService userService, final BenchmarkAggregationService benchmarks) {
        this.userService = userService;
        this.benchmarks = benchmarks;
    }

    @GetMapping
    public ResponseEntity<List<BenchmarkAggregationService.BenchmarkRow>> get(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(required = false) final String incomeTier,
            @RequestParam(required = false) final Integer householdSize) {
        authenticate(userDetails);

        // Validate inputs against the whitelist. Nulls are allowed → default bucket.
        if (incomeTier != null
                && !incomeTier.isBlank()
                && !ALLOWED_INCOME_TIERS.contains(incomeTier)) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "Unknown income tier: " + incomeTier + " — use one of " + ALLOWED_INCOME_TIERS);
        }
        if (householdSize != null && (householdSize < 1 || householdSize > 12)) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "Household size out of range: " + householdSize + " — must be 1–12");
        }

        // Server-side cached (Caffeine @Cacheable below) + HTTP cache-control so
        // CDN / client caches hold the response for up to an hour.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(benchmarksFor(incomeTier, householdSize));
    }

    /** Cached lookup — key on the bucketing inputs, 1-hour TTL via Caffeine config. */
    @Cacheable(
            value = "benchmarks",
            key =
                    "T(java.util.Objects).toString(#incomeTier, 'default') + ':' + T(java.util.Objects).toString(#householdSize, 'default')")
    public List<BenchmarkAggregationService.BenchmarkRow> benchmarksFor(
            final String incomeTier, final Integer householdSize) {
        return benchmarks.benchmarksFor(incomeTier, householdSize);
    }

    private UserTable authenticate(final UserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        return userService
                .findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
    }
}
