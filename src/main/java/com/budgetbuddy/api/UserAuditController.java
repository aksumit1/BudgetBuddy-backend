package com.budgetbuddy.api;

import com.budgetbuddy.compliance.AuditLogTable;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.budgetbuddy.service.UserService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 7 / O6 — user-facing audit log view.
 *
 * <p>Compliance endpoints already exist (admin-only), but end users had no way to see their own
 * login / edit history. This controller fills that gap with a single paginated endpoint: {@code GET
 * /api/audit/me?days=30&limit=100}. No filtering by action type yet — the list is small enough that
 * client-side filtering works — but the endpoint returns rows newest-first so pagination is
 * straightforward.
 *
 * <p>A separate export flow returns the same window as JSON, matching the GDPR "give me my data"
 * pattern.
 */
@RestController
@RequestMapping("/api/audit")
public class UserAuditController {

    private final AuditLogRepository auditRepository;
    private final UserService userService;

    public UserAuditController(
            final AuditLogRepository auditRepository, final UserService userService) {
        this.auditRepository = auditRepository;
        this.userService = userService;
    }

    /** Recent audit events for the current user, newest first. */
    @GetMapping("/me")
    public ResponseEntity<List<Map<String, Object>>> myEvents(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "30") final int days,
            @RequestParam(defaultValue = "200") final int limit) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final long to = Instant.now().getEpochSecond();
        final long from =
                Instant.now().minus(Math.min(days, 365), ChronoUnit.DAYS).getEpochSecond();
        List<AuditLogTable> rows =
                auditRepository.findByUserIdAndDateRange(user.getUserId(), from, to);

        rows.sort(
                (a, b) -> {
                    final Long ta = a.getCreatedAt() == null ? 0L : a.getCreatedAt();
                    final Long tb = b.getCreatedAt() == null ? 0L : b.getCreatedAt();
                    return tb.compareTo(ta);
                });
        final int cap = Math.max(1, Math.min(limit, 1000));
        if (rows.size() > cap) {
            rows = rows.subList(0, cap);
        }

        final List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (final AuditLogTable row : rows) {
            out.add(toMap(row));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * GDPR-style self-export. Same window as /me but no limit cap — the user asked for their data,
     * we give them their data. Caller picks the timeframe.
     */
    @GetMapping("/me/export")
    public ResponseEntity<List<Map<String, Object>>> export(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "365") final int days) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final long to = Instant.now().getEpochSecond();
        final long from =
                Instant.now().minus(Math.min(days, 365 * 7), ChronoUnit.DAYS).getEpochSecond();
        final List<AuditLogTable> rows =
                auditRepository.findByUserIdAndDateRange(user.getUserId(), from, to);
        final List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (final AuditLogTable row : rows) {
            out.add(toMap(row));
        }
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> toMap(final AuditLogTable row) {
        final Map<String, Object> m = new java.util.HashMap<>();
        m.put("auditLogId", row.getAuditLogId());
        m.put("action", row.getAction());
        m.put("resourceType", row.getResourceType());
        m.put("resourceId", row.getResourceId());
        m.put("details", row.getDetails());
        m.put("ipAddress", row.getIpAddress());
        m.put("userAgent", row.getUserAgent());
        // getCreatedAt() is epoch seconds (Long). Return as an ISO string so the client
        // never has to guess whether it's seconds or millis.
        m.put(
                "createdAt",
                row.getCreatedAt() == null
                        ? null
                        : Instant.ofEpochSecond(row.getCreatedAt()).toString());
        return m;
    }
}
