package com.budgetbuddy.compliance;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Flow 7 / O4 — single entry point that controllers call after every write. Before this existed,
 * only security-relevant events (login, password change, suspicious txn) landed in the audit trail;
 * Flow 4/5/6 mutation routes silently bypassed audit.
 *
 * <p>The API is tiny on purpose: `budgetChanged(user, id, before, after)` and the goal equivalents.
 * The interceptor derives the "what changed" diff and routes to {@link AuditLogService}. Failures
 * here never throw back to the user — analytics shouldn't be able to break a save.
 */
// SDK / Spring / reflection integration — broad catches translate any
// runtime exception to AppException or log+swallow. Narrowing isn't
// practical here, so suppress at class level.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class MutationAuditInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MutationAuditInterceptor.class);

    private final AuditLogService auditLogService;
    private final UserService userService;

    public MutationAuditInterceptor(
            final AuditLogService auditLogService, final UserService userService) {
        this.auditLogService = auditLogService;
        this.userService = userService;
    }

    public void budgetChanged(
            final String userId, final String budgetId, final String verb, final String summary) {
        safeLog("BUDGET", userId, budgetId, verb, summary);
    }

    public void goalChanged(
            final String userId, final String goalId, final String verb, final String summary) {
        safeLog("GOAL", userId, goalId, verb, summary);
    }

    public void transactionChanged(
            final String userId,
            final String transactionId,
            final String verb,
            final String summary) {
        safeLog("TRANSACTION", userId, transactionId, verb, summary);
    }

    /** Attempt to log; swallow failures to protect the user-visible write path. */
    private void safeLog(
            final String entityType,
            final String userId,
            final String entityId,
            final String verb,
            final String summary) {
        try {
            final java.util.Map<String, Object> details = new java.util.HashMap<>();
            if (summary != null) {
                details.put("summary", summary);
            }
            details.put("verb", verb);
            auditLogService.logAction(
                    userId,
                    "DATA_"
                            + verb.toUpperCase(
                                    Locale.ROOT), // DATA_CREATE / DATA_UPDATE / DATA_DELETE
                    entityType,
                    entityId,
                    details,
                    /* ipAddress */ null,
                    /* userAgent */ null);
        } catch (Exception e) {
            LOGGER.warn("Mutation audit log failed ({} {}): {}", entityType, verb, e.getMessage());
        }
    }

}
