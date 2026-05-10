package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.notification.DailyReadEmailService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opt-in / opt-out endpoint for the daily-read email delivery. iOS {@code
 * DailyReadNotificationSettingsView} calls these when the user flips the morning-read toggle.
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/notifications/daily-read-email")
public class DailyReadEmailController {

    private final UserService userService;
    private final DailyReadEmailService dailyReadEmail;

    public DailyReadEmailController(
            final UserService userService, final DailyReadEmailService dailyReadEmail) {
        this.userService = userService;
        this.dailyReadEmail = dailyReadEmail;
    }

    @GetMapping
    public ResponseEntity<Map<String, Boolean>> status(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = authenticate(userDetails);
        return ResponseEntity.ok(Map.of("optedIn", dailyReadEmail.isOptedIn(user.getUserId())));
    }

    @PutMapping
    public ResponseEntity<Void> set(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final SetOptInRequest body) {
        final UserTable user = authenticate(userDetails);
        dailyReadEmail.setOptedIn(user.getUserId(), body.optedIn);
        return ResponseEntity.noContent().build();
    }

    public static class SetOptInRequest {
        public boolean optedIn;
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
