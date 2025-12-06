package com.budgetbuddy.security.behavioral;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behavioral Analysis Service
 * Implements continuous monitoring and behavioral analysis for Zero Trust
 * Detects anomalies and calculates risk scores based on user behavior patterns
 * 
 * Features:
 * - User behavior profiling
 * - Anomaly detection
 * - Risk scoring
 * - Adaptive authentication
 * - Threat detection
 */
@Service
public class BehavioralAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(BehavioralAnalysisService.class);

    // In-memory storage for behavior profiles (in production, store in DynamoDB)
    // Key: userId, Value: BehaviorProfile
    private final ConcurrentHashMap<String, BehaviorProfile> behaviorProfiles = new ConcurrentHashMap<>();

    // In-memory storage for recent activities (in production, use time-series database)
    // Key: userId, Value: List of recent activities
    private final ConcurrentHashMap<String, List<Activity>> recentActivities = new ConcurrentHashMap<>();

    // Configuration
    private static final int ACTIVITY_HISTORY_SIZE = 100; // Keep last 100 activities
    private static final long ANOMALY_DETECTION_WINDOW_SECONDS = 3600; // 1 hour
    private static final double HIGH_RISK_THRESHOLD = 70.0;
    private static final double MEDIUM_RISK_THRESHOLD = 40.0;

    /**
     * Record user activity for behavioral analysis
     */
    public void recordActivity(final String userId, final ActivityType type, final String resource, final String action, final Map<String, String> metadata) {
        if (userId == null || userId.isEmpty()) {
            return;
        }

        Activity activity = new Activity(
                userId,
                type,
                resource,
                action,
                Instant.now(),
                metadata != null ? metadata : new HashMap<>()
        );

        // Add to recent activities
        recentActivities.computeIfAbsent(userId, k -> new ArrayList<>()).add(activity);

        // Maintain history size
        List<Activity> activities = recentActivities.get(userId);
        if (activities.size() > ACTIVITY_HISTORY_SIZE) {
            activities.remove(0); // Remove oldest
        }

        // Update behavior profile
        updateBehaviorProfile(userId, activity);

        logger.debug("Activity recorded: User={}, Type={}, Resource={}, Action={}", userId, type, resource, action);
    }

    /**
     * Calculate risk score for a user activity
     * Returns risk score (0-100, higher = more risky)
     */
    public RiskScore calculateRiskScore(final String userId, final ActivityType type, final String resource, final String action, final Map<String, String> context) {
        RiskScore riskScore = new RiskScore();
        riskScore.setUserId(userId);
        riskScore.setTimestamp(Instant.now());

        // Factor 1: Time-based anomaly (unusual time of day)
        int timeAnomalyScore = calculateTimeAnomaly(userId, type);
        riskScore.addFactor("timeAnomaly", timeAnomalyScore);

        // Factor 2: Location anomaly (if available)
        int locationAnomalyScore = calculateLocationAnomaly(userId, context);
        riskScore.addFactor("locationAnomaly", locationAnomalyScore);

        // Factor 3: Frequency anomaly (unusual activity frequency)
        int frequencyAnomalyScore = calculateFrequencyAnomaly(userId, type);
        riskScore.addFactor("frequencyAnomaly", frequencyAnomalyScore);

        // Factor 4: Resource sensitivity
        int resourceSensitivityScore = calculateResourceSensitivity(resource);
        riskScore.addFactor("resourceSensitivity", resourceSensitivityScore);

        // Factor 5: Action sensitivity
        int actionSensitivityScore = calculateActionSensitivity(action);
        riskScore.addFactor("actionSensitivity", actionSensitivityScore);

        // Factor 6: Device anomaly (if available)
        int deviceAnomalyScore = calculateDeviceAnomaly(userId, context);
        riskScore.addFactor("deviceAnomaly", deviceAnomalyScore);

        // Factor 7: Behavioral pattern deviation
        int patternDeviationScore = calculatePatternDeviation(userId, type, resource, action);
        riskScore.addFactor("patternDeviation", patternDeviationScore);

        // Calculate final score (weighted average)
        double finalScore = riskScore.calculateFinalScore();
        riskScore.setScore(finalScore);

        // Determine risk level
        if (finalScore >= HIGH_RISK_THRESHOLD) {
            riskScore.setRiskLevel(RiskLevel.HIGH);
        } else if (finalScore >= MEDIUM_RISK_THRESHOLD) {
            riskScore.setRiskLevel(RiskLevel.MEDIUM);
        } else {
            riskScore.setRiskLevel(RiskLevel.LOW);
        }

        logger.debug("Risk score calculated: User={}, Score={}, Level={}", userId, finalScore, riskScore.getRiskLevel());

        return riskScore;
    }

    /**
     * Detect anomalies in user behavior
     */
    public List<Anomaly> detectAnomalies(final String userId) {
        List<Anomaly> anomalies = new ArrayList<>();
        BehaviorProfile profile = behaviorProfiles.get(userId);

        if (profile == null) {
            return anomalies; // No profile yet, no anomalies
        }

        List<Activity> recent = recentActivities.getOrDefault(userId, Collections.emptyList());
        if (recent.isEmpty()) {
            return anomalies;
        }

        // Filter activities within detection window
        Instant windowStart = Instant.now().minusSeconds(ANOMALY_DETECTION_WINDOW_SECONDS);
        List<Activity> windowActivities = recent.stream()
                .filter(a -> a.getTimestamp().isAfter(windowStart))
                .toList();

        // Check for anomalies
        // 1. Unusual activity frequency
        if (windowActivities.size() > profile.getAverageActivitiesPerHour() * 3) {
            anomalies.add(new Anomaly(
                    AnomalyType.UNUSUAL_FREQUENCY,
                    "Activity frequency significantly higher than normal",
                    Instant.now()
            ));
        }

        // 2. Unusual resource access
        Set<String> accessedResources = new HashSet<>();
        windowActivities.forEach(a -> accessedResources.add(a.getResource()));
        if (accessedResources.size() > profile.getAverageResourcesPerHour() * 2) {
            anomalies.add(new Anomaly(
                    AnomalyType.UNUSUAL_RESOURCE_ACCESS,
                    "Accessing more resources than normal",
                    Instant.now()
            ));
        }

        // 3. Unusual time pattern
        long unusualTimeCount = windowActivities.stream()
                .filter(a -> isUnusualTime(userId, a.getTimestamp()))
                .count();
        if (unusualTimeCount > windowActivities.size() * 0.5) {
            anomalies.add(new Anomaly(
                    AnomalyType.UNUSUAL_TIME_PATTERN,
                    "Activity at unusual times",
                    Instant.now()
            ));
        }

        return anomalies;
    }

    /**
     * Get behavior profile for a user
     */
    public BehaviorProfile getBehaviorProfile(final String userId) {
        return behaviorProfiles.getOrDefault(userId, new BehaviorProfile(userId));
    }

    // MARK: - Private Helper Methods

    /**
     * Update behavior profile based on new activity
     */
    private void updateBehaviorProfile(final String userId, final Activity activity) {
        BehaviorProfile profile = behaviorProfiles.computeIfAbsent(userId, k -> new BehaviorProfile(userId));

        // Update activity counts
        profile.incrementActivityCount(activity.getType());

        // Update time patterns
        int hour = activity.getTimestamp().atZone(java.time.ZoneId.systemDefault()).getHour();
        profile.recordActivityTime(hour);

        // Update resource access patterns
        profile.recordResourceAccess(activity.getResource());

        // Update action patterns
        profile.recordAction(activity.getAction());

        // Recalculate averages
        profile.recalculateAverages();
    }

    /**
     * Calculate time-based anomaly score
     */
    private int calculateTimeAnomaly(final String userId, final ActivityType type) {
        BehaviorProfile profile = behaviorProfiles.get(userId);
        if (profile == null) {
            return 0; // No profile, no anomaly
        }

        int currentHour = Instant.now().atZone(java.time.ZoneId.systemDefault()).getHour();
        double activityAtThisHour = profile.getActivityFrequencyAtHour(currentHour);

        // If activity at this hour is < 10% of average, it's unusual
        if (activityAtThisHour < 0.1) {
            return 60; // High anomaly
        } else if (activityAtThisHour < 0.3) {
            return 30; // Medium anomaly
        }

        return 0; // Normal
    }

    /**
     * Calculate location-based anomaly score
     */
    private int calculateLocationAnomaly(final String userId, final Map<String, String> context) {
        // In production, compare with known locations
        // For now, return 0 (no location data)
        return 0;
    }

    /**
     * Calculate frequency-based anomaly score
     */
    private int calculateFrequencyAnomaly(final String userId, final ActivityType type) {
        BehaviorProfile profile = behaviorProfiles.get(userId);
        if (profile == null) {
            return 0;
        }

        List<Activity> recent = recentActivities.getOrDefault(userId, Collections.emptyList());
        Instant windowStart = Instant.now().minusSeconds(ANOMALY_DETECTION_WINDOW_SECONDS);
        long recentCount = recent.stream()
                .filter(a -> a.getTimestamp().isAfter(windowStart))
                .count();

        double average = profile.getAverageActivitiesPerHour();
        if (recentCount > average * 3) {
            return 70; // Very high frequency
        } else if (recentCount > average * 2) {
            return 40; // High frequency
        } else if (recentCount < average * 0.3) {
            return 20; // Low frequency (might be suspicious)
        }

        return 0; // Normal
    }

    /**
     * Calculate resource sensitivity score
     */
    private int calculateResourceSensitivity(final String resource) {
        if (resource == null || resource.isEmpty()) {
            return 0;
        }

        // High sensitivity resources
        if (resource.contains("/admin") || resource.contains("/compliance") || resource.contains("/audit")) {
            return 80;
        }
        // Medium sensitivity
        if (resource.contains("/api/transactions") || resource.contains("/api/accounts") || resource.contains("/api/budgets")) {
            return 50;
        }
        // Low sensitivity
        return 20;
    }

    /**
     * Calculate action sensitivity score
     */
    private int calculateActionSensitivity(final String action) {
        if (action == null || action.isEmpty()) {
            return 0;
        }

        // High sensitivity actions
        if ("DELETE".equalsIgnoreCase(action) || "UPDATE".equalsIgnoreCase(action)) {
            return 70;
        }
        // Medium sensitivity
        if ("CREATE".equalsIgnoreCase(action)) {
            return 40;
        }
        // Low sensitivity (READ)
        return 10;
    }

    /**
     * Calculate device-based anomaly score
     */
    private int calculateDeviceAnomaly(final String userId, final Map<String, String> context) {
        // In production, compare with known devices
        // For now, return 0 (no device data)
        return 0;
    }

    /**
     * Calculate behavioral pattern deviation score
     */
    private int calculatePatternDeviation(final String userId, final ActivityType type, final String resource, final String action) {
        BehaviorProfile profile = behaviorProfiles.get(userId);
        if (profile == null) {
            return 0; // No profile, no deviation
        }

        // Check if this type of activity is common for this user
        double typeFrequency = profile.getActivityTypeFrequency(type);
        if (typeFrequency < 0.1) {
            return 50; // Unusual activity type
        }

        // Check if this resource is commonly accessed
        double resourceFrequency = profile.getResourceFrequency(resource);
        if (resourceFrequency < 0.1) {
            return 40; // Unusual resource
        }

        // Check if this action is common
        double actionFrequency = profile.getActionFrequency(action);
        if (actionFrequency < 0.1) {
            return 30; // Unusual action
        }

        return 0; // Normal pattern
    }

    /**
     * Check if time is unusual for user
     */
    private boolean isUnusualTime(final String userId, final Instant timestamp) {
        BehaviorProfile profile = behaviorProfiles.get(userId);
        if (profile == null) {
            return false;
        }

        int hour = timestamp.atZone(java.time.ZoneId.systemDefault()).getHour();
        double frequency = profile.getActivityFrequencyAtHour(hour);
        return frequency < 0.1; // Less than 10% of normal activity
    }

    // MARK: - Inner Classes

    /**
     * Activity type
     */
    public enum ActivityType {
        AUTHENTICATION,
        DATA_ACCESS,
        DATA_MODIFICATION,
        DATA_DELETION,
        ADMIN_ACTION,
        COMPLIANCE_ACTION,
        API_CALL,
        EXPORT,
        IMPORT
    }

    /**
     * Activity record
     */
    public static class Activity {
        private final String userId;
        private final ActivityType type;
        private final String resource;
        private final String action;
        private final Instant timestamp;
        private final Map<String, String> metadata;

        public Activity(final String userId, final ActivityType type, final String resource, 
                       final String action, final Instant timestamp, final Map<String, String> metadata) {
            this.userId = userId;
            this.type = type;
            this.resource = resource;
            this.action = action;
            this.timestamp = timestamp;
            this.metadata = metadata;
        }

        public String getUserId() { return userId; }
        public ActivityType getType() { return type; }
        public String getResource() { return resource; }
        public String getAction() { return action; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, String> getMetadata() { return metadata; }
    }

    /**
     * Risk score
     */
    public static class RiskScore {
        private String userId;
        private Instant timestamp;
        private final Map<String, Integer> factors = new HashMap<>();
        private double score;
        private RiskLevel riskLevel;

        public void addFactor(final String name, final int value) {
            factors.put(name, value);
        }

        public double calculateFinalScore() {
            if (factors.isEmpty()) {
                return 0.0;
            }

            // Weighted average (some factors are more important)
            double weightedSum = 0.0;
            double totalWeight = 0.0;

            for (Map.Entry<String, Integer> entry : factors.entrySet()) {
                double weight = getFactorWeight(entry.getKey());
                weightedSum += entry.getValue() * weight;
                totalWeight += weight;
            }

            return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
        }

        private double getFactorWeight(final String factorName) {
            // Higher weight for more critical factors
            return switch (factorName) {
                case "patternDeviation" -> 1.5; // Most important
                case "resourceSensitivity" -> 1.3;
                case "actionSensitivity" -> 1.2;
                case "timeAnomaly" -> 1.0;
                case "locationAnomaly" -> 1.0;
                case "frequencyAnomaly" -> 0.8;
                case "deviceAnomaly" -> 0.7;
                default -> 1.0;
            };
        }

        // Getters and setters
        public String getUserId() { return userId; }
        public void setUserId(final String userId) { this.userId = userId; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
        public Map<String, Integer> getFactors() { return factors; }
        public double getScore() { return score; }
        public void setScore(final double score) { this.score = score; }
        public RiskLevel getRiskLevel() { return riskLevel; }
        public void setRiskLevel(final RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    }

    /**
     * Risk level
     */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * Anomaly
     */
    public static class Anomaly {
        private final AnomalyType type;
        private final String description;
        private final Instant detectedAt;

        public Anomaly(final AnomalyType type, final String description, final Instant detectedAt) {
            this.type = type;
            this.description = description;
            this.detectedAt = detectedAt;
        }

        public AnomalyType getType() { return type; }
        public String getDescription() { return description; }
        public Instant getDetectedAt() { return detectedAt; }
    }

    /**
     * Anomaly type
     */
    public enum AnomalyType {
        UNUSUAL_FREQUENCY,
        UNUSUAL_TIME_PATTERN,
        UNUSUAL_RESOURCE_ACCESS,
        UNUSUAL_LOCATION,
        UNUSUAL_DEVICE,
        PATTERN_DEVIATION
    }

    /**
     * Behavior profile
     */
    public static class BehaviorProfile {
        private final String userId;
        private final Map<ActivityType, Integer> activityCounts = new HashMap<>();
        private final Map<Integer, Integer> hourlyActivityCounts = new HashMap<>(); // Hour -> Count
        private final Map<String, Integer> resourceAccessCounts = new HashMap<>();
        private final Map<String, Integer> actionCounts = new HashMap<>();
        private double averageActivitiesPerHour = 0.0;
        private double averageResourcesPerHour = 0.0;
        private Instant profileCreatedAt = Instant.now();

        public BehaviorProfile(final String userId) {
            this.userId = userId;
        }

        public void incrementActivityCount(final ActivityType type) {
            activityCounts.put(type, activityCounts.getOrDefault(type, 0) + 1);
        }

        public void recordActivityTime(final int hour) {
            hourlyActivityCounts.put(hour, hourlyActivityCounts.getOrDefault(hour, 0) + 1);
        }

        public void recordResourceAccess(final String resource) {
            resourceAccessCounts.put(resource, resourceAccessCounts.getOrDefault(resource, 0) + 1);
        }

        public void recordAction(final String action) {
            actionCounts.put(action, actionCounts.getOrDefault(action, 0) + 1);
        }

        public void recalculateAverages() {
            // Calculate average activities per hour (based on profile age)
            long profileAgeHours = java.time.Duration.between(profileCreatedAt, Instant.now()).toHours();
            if (profileAgeHours > 0) {
                int totalActivities = activityCounts.values().stream().mapToInt(Integer::intValue).sum();
                averageActivitiesPerHour = (double) totalActivities / profileAgeHours;
            }

            // Calculate average resources per hour
            if (profileAgeHours > 0) {
                int uniqueResources = resourceAccessCounts.size();
                averageResourcesPerHour = (double) uniqueResources / profileAgeHours;
            }
        }

        public double getActivityFrequencyAtHour(final int hour) {
            int count = hourlyActivityCounts.getOrDefault(hour, 0);
            int total = hourlyActivityCounts.values().stream().mapToInt(Integer::intValue).sum();
            return total > 0 ? (double) count / total : 0.0;
        }

        public double getActivityTypeFrequency(final ActivityType type) {
            int count = activityCounts.getOrDefault(type, 0);
            int total = activityCounts.values().stream().mapToInt(Integer::intValue).sum();
            return total > 0 ? (double) count / total : 0.0;
        }

        public double getResourceFrequency(final String resource) {
            int count = resourceAccessCounts.getOrDefault(resource, 0);
            int total = resourceAccessCounts.values().stream().mapToInt(Integer::intValue).sum();
            return total > 0 ? (double) count / total : 0.0;
        }

        public double getActionFrequency(final String action) {
            int count = actionCounts.getOrDefault(action, 0);
            int total = actionCounts.values().stream().mapToInt(Integer::intValue).sum();
            return total > 0 ? (double) count / total : 0.0;
        }

        // Getters
        public String getUserId() { return userId; }
        public double getAverageActivitiesPerHour() { return averageActivitiesPerHour; }
        public double getAverageResourcesPerHour() { return averageResourcesPerHour; }
    }
}

