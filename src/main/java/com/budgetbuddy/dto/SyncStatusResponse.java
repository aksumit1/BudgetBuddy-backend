package com.budgetbuddy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Response DTO for /api/sync/status endpoint Returns sync status information for offline mode
 * support
 */
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings("PMD.DataClass")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances")
public class SyncStatusResponse {

    @JsonProperty("isOnline")
    private boolean isOnline;

    @JsonProperty("lastSyncTimestamp")
    private Long lastSyncTimestamp; // Epoch seconds

    @JsonProperty("pendingSyncCount")
    private Integer pendingSyncCount;

    @JsonProperty("syncStatus")
    private SyncStatus syncStatus;

    @JsonProperty("dataCounts")
    private DataCounts dataCounts;

    @JsonProperty("serverTime")
    private Long serverTime; // Epoch seconds - for clock sync

    public SyncStatusResponse() {}

    public SyncStatusResponse(
            final boolean isOnline,
            final Long lastSyncTimestamp,
            final Integer pendingSyncCount,
            final SyncStatus syncStatus,
            final DataCounts dataCounts,
            final Long serverTime) {
        this.isOnline = isOnline;
        this.lastSyncTimestamp = lastSyncTimestamp;
        this.pendingSyncCount = pendingSyncCount;
        this.syncStatus = syncStatus;
        this.dataCounts = dataCounts;
        this.serverTime = serverTime;
    }

    // Getters and Setters
    public boolean isOnline() {
        return isOnline;
    }

    public void setOnline(final boolean isOnline) {
        this.isOnline = isOnline;
    }

    public Long getLastSyncTimestamp() {
        return lastSyncTimestamp;
    }

    public void setLastSyncTimestamp(final Long lastSyncTimestamp) {
        this.lastSyncTimestamp = lastSyncTimestamp;
    }

    public Integer getPendingSyncCount() {
        return pendingSyncCount;
    }

    public void setPendingSyncCount(final Integer pendingSyncCount) {
        this.pendingSyncCount = pendingSyncCount;
    }

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(final SyncStatus syncStatus) {
        this.syncStatus = syncStatus;
    }

    public DataCounts getDataCounts() {
        return dataCounts;
    }

    public void setDataCounts(final DataCounts dataCounts) {
        this.dataCounts = dataCounts;
    }

    public Long getServerTime() {
        return serverTime;
    }

    public void setServerTime(final Long serverTime) {
        this.serverTime = serverTime;
    }

    /** Sync status enum */
    public enum SyncStatus {
        IDLE,
        SYNCING,
        ERROR,
        PAUSED
    }

    /** Data counts for sync status */
    public static class DataCounts {
        @JsonProperty("accounts")
        private Integer accounts;

        @JsonProperty("transactions")
        private Integer transactions;

        @JsonProperty("budgets")
        private Integer budgets;

        @JsonProperty("goals")
        private Integer goals;

        public DataCounts() {}

        public DataCounts(
                final Integer accounts,
                final Integer transactions,
                final Integer budgets,
                final Integer goals) {
            this.accounts = accounts;
            this.transactions = transactions;
            this.budgets = budgets;
            this.goals = goals;
        }

        // Getters and Setters
        public Integer getAccounts() {
            return accounts;
        }

        public void setAccounts(final Integer accounts) {
            this.accounts = accounts;
        }

        public Integer getTransactions() {
            return transactions;
        }

        public void setTransactions(final Integer transactions) {
            this.transactions = transactions;
        }

        public Integer getBudgets() {
            return budgets;
        }

        public void setBudgets(final Integer budgets) {
            this.budgets = budgets;
        }

        public Integer getGoals() {
            return goals;
        }

        public void setGoals(final Integer goals) {
            this.goals = goals;
        }
    }
}
