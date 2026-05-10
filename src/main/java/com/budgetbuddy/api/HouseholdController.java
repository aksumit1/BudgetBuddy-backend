package com.budgetbuddy.api;


import java.util.Locale;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.household.HouseholdService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner / household sharing endpoints. Mirrors iOS {@code HouseholdStore}.
 *
 * <p>Status: scaffold. Persistence is in-memory on {@link HouseholdService} until the DynamoDB
 * household table + audit trail lands. The iOS client already treats invitation state as
 * optimistic-local, so these endpoints are safe to ship behind a feature flag.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@RestController
@RequestMapping("/api/household")
public class HouseholdController {

    private final UserService userService;
    private final HouseholdService householdService;

    public HouseholdController(
            final UserService userService, final HouseholdService householdService) {
        this.userService = userService;
        this.householdService = householdService;
    }

    @PostMapping("/invite")
    public ResponseEntity<Map<String, Object>> invite(
            @AuthenticationPrincipal final UserDetails userDetails, @RequestBody final InviteRequest body) {
        final UserTable user = authenticate(userDetails);
        if (body.email == null || !body.email.contains("@") || !body.email.contains(".")) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email looks invalid");
        }
        final HouseholdService.Invite invite =
                householdService.invite(user.getUserId(), body.email.toLowerCase(Locale.ROOT).trim());
        return ResponseEntity.ok(
                Map.of(
                        "email",
                        invite.email,
                        "sentAt",
                        invite.sentAt.toString(),
                        "pending",
                        invite.acceptedAt == null));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> current(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = authenticate(userDetails);
        final HouseholdService.Invite invite = householdService.currentInvite(user.getUserId());
        if (invite == null) {
            return ResponseEntity.ok(Map.of("invited", false));
        }
        return ResponseEntity.ok(
                Map.of(
                        "invited",
                        true,
                        "email",
                        invite.email,
                        "sentAt",
                        invite.sentAt.toString(),
                        "acceptedAt",
                        invite.acceptedAt == null ? "" : invite.acceptedAt.toString()));
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = authenticate(userDetails);
        householdService.revoke(user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/preferences")
    public ResponseEntity<Void> updatePreferences(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final PreferencesRequest body) {
        final UserTable user = authenticate(userDetails);
        householdService.updatePreferences(
                user.getUserId(),
                body.shareNetWorth,
                body.shareGoals,
                body.shareBudgets,
                body.shareTransactions);
        return ResponseEntity.noContent().build();
    }

    // MARK - DTOs

    public static class InviteRequest {
        public String email;
    }

    public static class PreferencesRequest {
        public boolean shareNetWorth;
        public boolean shareGoals;
        public boolean shareBudgets;
        public boolean shareTransactions;
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
