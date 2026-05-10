package com.budgetbuddy.model.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

/**
 * Lightweight key-value preferences per user. One row per user.
 *
 * <p>Avoids polluting {@code UserTable} (hot path) with infrequently-updated notification + UI
 * preferences.
 */
@DynamoDbBean
public class UserPreferencesTable {

    private String userId; // partition key

    // Daily read email
    private Boolean dailyReadEmailEnabled;
    private Integer dailyReadEmailHourUtc; // 0-23

    // Weekly review reminder
    private Boolean weeklyReviewEnabled;
    private Integer weeklyReviewHourUtc;

    // Community benchmarks opt-in (controls whether this user's anonymised
    // spend contributes to the aggregation job).
    private Boolean shareAnonymisedStats;

    // Bucketing inputs — pre-bucketed tier string + integer size.
    private String annualIncomeTier;
    private Integer householdSize;

    // Sparse GSI partition keys. These are populated only while the flag is on,
    // so the GSI holds only opt-in rows. The daily-read cron + benchmark cron
    // query these GSIs instead of scanning the full table. Value is the string
    // "1" when opted-in (never "0" — null keeps the row out of the GSI).
    private String dailyReadEmailEnabledFlag;
    private String anonymisedStatsOptInFlag;

    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("dailyReadEmailEnabled")
    public Boolean getDailyReadEmailEnabled() {
        return dailyReadEmailEnabled;
    }

    public void setDailyReadEmailEnabled(final Boolean v) {
        this.dailyReadEmailEnabled = v;
    }

    @DynamoDbAttribute("dailyReadEmailHourUtc")
    public Integer getDailyReadEmailHourUtc() {
        return dailyReadEmailHourUtc;
    }

    public void setDailyReadEmailHourUtc(final Integer v) {
        this.dailyReadEmailHourUtc = v;
    }

    @DynamoDbAttribute("weeklyReviewEnabled")
    public Boolean getWeeklyReviewEnabled() {
        return weeklyReviewEnabled;
    }

    public void setWeeklyReviewEnabled(final Boolean v) {
        this.weeklyReviewEnabled = v;
    }

    @DynamoDbAttribute("weeklyReviewHourUtc")
    public Integer getWeeklyReviewHourUtc() {
        return weeklyReviewHourUtc;
    }

    public void setWeeklyReviewHourUtc(final Integer v) {
        this.weeklyReviewHourUtc = v;
    }

    @DynamoDbAttribute("shareAnonymisedStats")
    public Boolean getShareAnonymisedStats() {
        return shareAnonymisedStats;
    }

    public void setShareAnonymisedStats(final Boolean v) {
        this.shareAnonymisedStats = v;
    }

    /**
     * Pre-bucketed annual income tier string (e.g. "inc-75-150k"). We never ingest raw income
     * amounts into the aggregation pipeline — the user picks a bucket on opt-in and we just read
     * that.
     */
    @DynamoDbAttribute("annualIncomeTier")
    public String getAnnualIncomeTier() {
        return annualIncomeTier;
    }

    public void setAnnualIncomeTier(final String v) {
        this.annualIncomeTier = v;
    }

    @DynamoDbAttribute("householdSize")
    public Integer getHouseholdSize() {
        return householdSize;
    }

    public void setHouseholdSize(final Integer v) {
        this.householdSize = v;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"DailyReadEmailOptInIndex"})
    @DynamoDbAttribute("dailyReadEmailEnabledFlag")
    public String getDailyReadEmailEnabledFlag() {
        return dailyReadEmailEnabledFlag;
    }

    public void setDailyReadEmailEnabledFlag(final String v) {
        this.dailyReadEmailEnabledFlag = v;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"AnonymisedStatsOptInIndex"})
    @DynamoDbAttribute("anonymisedStatsOptInFlag")
    public String getAnonymisedStatsOptInFlag() {
        return anonymisedStatsOptInFlag;
    }

    public void setAnonymisedStatsOptInFlag(final String v) {
        this.anonymisedStatsOptInFlag = v;
    }

    @DynamoDbAttribute("updatedAt")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Instant v) {
        this.updatedAt = v;
    }
}
