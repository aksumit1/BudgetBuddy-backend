package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.NetWorthSnapshotTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.NetWorthSnapshotRepository;
import com.budgetbuddy.service.NetWorthSnapshotService;
import com.budgetbuddy.service.UserService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 7 / O8 — net-worth history for the trend chart on the overview dashboard. Two endpoints: -
 * {@code GET /api/networth/history?days=365} — rows since that window. - {@code POST
 * /api/networth/snapshot} — force-recompute today's snapshot without waiting for the nightly job.
 * Useful for "I just connected a new bank, show me the bump right now."
 */
@RestController
@RequestMapping("/api/networth")
public class NetWorthController {

    private final NetWorthSnapshotRepository repository;
    private final NetWorthSnapshotService service;
    private final UserService userService;

    public NetWorthController(
            final NetWorthSnapshotRepository repository,
            final NetWorthSnapshotService service,
            final UserService userService) {
        this.repository = repository;
        this.service = service;
        this.userService = userService;
    }

    @GetMapping("/history")
    public ResponseEntity<List<NetWorthSnapshotTable>> history(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "365") final int days) {
        final UserTable user = authenticate(userDetails);
        final LocalDate since = LocalDate.now().minusDays(Math.min(days, 365 * 5));
        return ResponseEntity.ok(repository.findByUserIdSince(user.getUserId(), since.toString()));
    }

    @PostMapping("/snapshot")
    public ResponseEntity<NetWorthSnapshotTable> snapshotNow(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = authenticate(userDetails);
        return ResponseEntity.ok(service.snapshotOne(user, LocalDate.now()));
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
