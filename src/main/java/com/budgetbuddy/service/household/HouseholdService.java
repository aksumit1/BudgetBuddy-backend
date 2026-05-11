package com.budgetbuddy.service.household;

import com.budgetbuddy.model.dynamodb.HouseholdTable;
import com.budgetbuddy.repository.dynamodb.HouseholdRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Household sharing service, backed by {@link HouseholdRepository}.
 *
 * <p>One row per inviter. When an invitee accepts, a signed-token endpoint (future commit) stamps
 * {@code acceptedAt} and grants the acceptor read scope keyed on the row's preferences.
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class HouseholdService {

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = {"URF_UNREAD_FIELD", "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
        justification = "DTO — fields are read/written by Jackson via reflection")
    public static class Preferences {
        public boolean shareNetWorth = true;
        public boolean shareGoals = true;
        public boolean shareBudgets = true;
        public boolean shareTransactions = false;
    }

    public static class Invite {
        public final String email;
        public final Instant sentAt;
        public Instant acceptedAt;

        public Invite(final String email, final Instant sentAt, final Instant acceptedAt) {
            this.email = email;
            this.sentAt = sentAt;
            this.acceptedAt = acceptedAt;
        }
    }

    private final HouseholdRepository repository;

    public HouseholdService(final HouseholdRepository repository) {
        this.repository = repository;
    }

    public Invite invite(final String userId, final String email) {
        final HouseholdTable row = repository.findByUserId(userId).orElseGet(HouseholdTable::new);
        row.setUserId(userId);
        row.setInviteeEmail(email);
        row.setSentAt(Instant.now());
        row.setAcceptedAt(null);
        // Defaults when creating the first invitation — mirror iOS defaults.
        if (row.getShareNetWorth() == null) {
            row.setShareNetWorth(true);
        }
        if (row.getShareGoals() == null) {
            row.setShareGoals(true);
        }
        if (row.getShareBudgets() == null) {
            row.setShareBudgets(true);
        }
        if (row.getShareTransactions() == null) {
            row.setShareTransactions(false);
        }
        repository.save(row);
        return new Invite(email, row.getSentAt(), row.getAcceptedAt());
    }

    public Invite currentInvite(final String userId) {
        final Optional<HouseholdTable> opt = repository.findByUserId(userId);
        if (opt.isEmpty() || opt.get().getInviteeEmail() == null) {
            return null;
        }
        final HouseholdTable r = opt.get();
        return new Invite(r.getInviteeEmail(), r.getSentAt(), r.getAcceptedAt());
    }

    public void revoke(final String userId) {
        repository.deleteByUserId(userId);
    }

    public Preferences preferences(final String userId) {
        final HouseholdTable row = repository.findByUserId(userId).orElseGet(HouseholdTable::new);
        final Preferences p = new Preferences();
        if (row.getShareNetWorth() != null) {
            p.shareNetWorth = row.getShareNetWorth();
        }
        if (row.getShareGoals() != null) {
            p.shareGoals = row.getShareGoals();
        }
        if (row.getShareBudgets() != null) {
            p.shareBudgets = row.getShareBudgets();
        }
        if (row.getShareTransactions() != null) {
            p.shareTransactions = row.getShareTransactions();
        }
        return p;
    }

    public void updatePreferences(
            final String userId,
            final boolean netWorth,
            final boolean goals,
            final boolean budgets,
            final boolean transactions) {
        final HouseholdTable row = repository.findByUserId(userId).orElseGet(HouseholdTable::new);
        row.setUserId(userId);
        row.setShareNetWorth(netWorth);
        row.setShareGoals(goals);
        row.setShareBudgets(budgets);
        row.setShareTransactions(transactions);
        repository.save(row);
    }
}
