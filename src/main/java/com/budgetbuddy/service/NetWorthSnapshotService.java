package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.NetWorthSnapshotTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.NetWorthSnapshotRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

// (List import retained for snapshotOne's accounts query.)

/**
 * Flow 7 / O8 — nightly net-worth snapshot job.
 *
 * <p>At 02:30 UTC every day we walk the user table, for each user sum asset and liability balances
 * from their accounts, and write a daily snapshot. The trend chart consumes these via {@link
 * NetWorthSnapshotRepository#findByUserIdSince}.
 *
 * <p>Convention: assets = positive-balance accounts (checking, savings, investment), liabilities =
 * negative-balance accounts (credit cards, loans). We treat the raw balance sign as authoritative —
 * same rule the iOS dashboard already uses.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class NetWorthSnapshotService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetWorthSnapshotService.class);

    private final NetWorthSnapshotRepository snapshotRepository;
    private final AccountRepository accountRepository;
    private final DynamoDbTable<UserTable> userTable;

    public NetWorthSnapshotService(
            final NetWorthSnapshotRepository snapshotRepository,
            final AccountRepository accountRepository,
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.snapshotRepository = snapshotRepository;
        this.accountRepository = accountRepository;
        // UserRepository doesn't expose findAll — scan the table directly here for the
        // nightly job. The users table is small enough that a scan is fine.
        this.userTable =
                enhancedClient.table(tablePrefix + "-Users", TableSchema.fromBean(UserTable.class));
    }

    /** Cron: 02:30 UTC daily. Off-peak; small enough scan that nightly is fine. */
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void nightlySnapshot() {
        try {
            final LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            int users = 0;
            final var pages = userTable.scan();
            for (final var page : pages) {
                for (final UserTable user : page.items()) {
                    users++;
                    try {
                        snapshotOne(user, today);
                    } catch (Exception e) {
                        LOGGER.warn(
                                "Net-worth snapshot failed for user {}: {}",
                                user.getUserId(),
                                e.getMessage());
                    }
                }
            }
            LOGGER.info("Net-worth nightly snapshot complete for {} users", users);
        } catch (Exception e) {
            LOGGER.error("Net-worth nightly snapshot pass failed: {}", e.getMessage(), e);
        }
    }

    /** Used by the endpoint for on-demand recompute. Also drives the scheduled job. */
    public NetWorthSnapshotTable snapshotOne(final UserTable user, final LocalDate date) {
        final List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        for (final AccountTable a : accounts) {
            if (a == null || a.getBalance() == null) {
                continue;
            }
            if (a.getBalance().signum() >= 0) {
                assets = assets.add(a.getBalance());
            } else {
                liabilities = liabilities.add(a.getBalance().abs());
            }
        }
        final NetWorthSnapshotTable snapshot = new NetWorthSnapshotTable();
        snapshot.setSnapshotId(user.getUserId() + "|" + date.toString());
        snapshot.setUserId(user.getUserId());
        snapshot.setSnapshotDate(date.toString());
        snapshot.setAssetsTotal(assets);
        snapshot.setLiabilitiesTotal(liabilities);
        snapshot.setNetWorth(assets.subtract(liabilities));
        snapshot.setCurrencyCode(
                user.getPreferredCurrency() == null ? "USD" : user.getPreferredCurrency());
        snapshot.setCreatedAt(Instant.now());
        snapshotRepository.save(snapshot);
        return snapshot;
    }
}
