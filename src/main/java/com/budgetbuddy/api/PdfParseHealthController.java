package com.budgetbuddy.api;

import com.budgetbuddy.service.pdf.PdfTemplateMissTracker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint for PDF parse-health telemetry.
 *
 * <p>This is the UI-facing half of the "learn from real failures" loop we wired up this round.
 * Every time the structured template parsers all miss on a real statement, {@link
 * PdfTemplateMissTracker} records the event. This controller ranks those misses so an on-call
 * engineer (or a dashboard tile) can see which bank needs its template improved first.
 *
 * <h3>Endpoint</h3>
 *
 * <pre>
 *   GET /api/admin/pdf-parse-health
 *   GET /api/admin/pdf-parse-health?windowDays=30
 * </pre>
 *
 * <p>Returns the top institutions by template-miss count over the requested window (default: 7
 * days). Example response:
 *
 * <pre>
 * {
 *   "totalMissesSinceStartup": 142,
 *   "windowDays": 7,
 *   "rankings": [
 *     {
 *       "institution": "Citi",
 *       "accountType": "credit",
 *       "missCount": 34,
 *       "lastMissAt": "2026-04-15T21:12:08Z",
 *       "averageFallbackRows": 12.4
 *     },
 *     { "institution": "HSBC UK", ... }
 *   ]
 * }
 * </pre>
 *
 * <h3>How ops uses this</h3>
 *
 * <ul>
 *   <li>Wire into a dashboard: poll the endpoint every 5 minutes, chart top-10 institutions by miss
 *       count.
 *   <li>Set an SLA: any institution with missCount &gt; 20 in 7 days gets an eng ticket to
 *       write/improve that template.
 *   <li>When a new template ships, watch the corresponding row drop to zero. That's the loop
 *       closing.
 * </ul>
 *
 * <h3>Security</h3>
 *
 * Mounted under {@code /api/admin/} — the existing security config should require admin-role
 * authentication for this path. This controller does not expose any user data, just aggregate
 * bank-level counters.
 */
@RestController
@RequestMapping("/api/admin")
public class PdfParseHealthController {

    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int MAX_WINDOW_DAYS = 90;

    private final PdfTemplateMissTracker tracker;

    public PdfParseHealthController(final PdfTemplateMissTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/pdf-parse-health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestParam(name = "windowDays", required = false) final Integer windowDays) {

        final int effectiveWindow =
                windowDays == null
                        ? DEFAULT_WINDOW_DAYS
                        : Math.min(Math.max(1, windowDays), MAX_WINDOW_DAYS);

        final List<PdfTemplateMissTracker.InstitutionMissRanking> rankings =
                tracker.rankByMissesIn(Duration.ofDays(effectiveWindow));

        final Map<String, Object> body =
                Map.of(
                        "totalMissesSinceStartup", tracker.totalMisses(),
                        "windowDays", effectiveWindow,
                        "rankings", rankings);
        return ResponseEntity.ok(body);
    }
}
