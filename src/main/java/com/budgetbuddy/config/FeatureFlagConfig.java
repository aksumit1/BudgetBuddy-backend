package com.budgetbuddy.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Feature Flag Configuration Enables feature toggling for gradual rollouts and A/B testing */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Configuration
public class FeatureFlagConfig {

    @Value("${app.features.enable-plaid:true}")
    private boolean enablePlaid;

    @Value("${app.features.enable-stripe:true}")
    private boolean enableStripe;

    @Value("${app.features.enable-oauth2:false}")
    private boolean enableOAuth2;

    @Value("${app.features.enable-advanced-analytics:false}")
    private boolean enableAdvancedAnalytics;

    @Value("${app.features.enable-notifications:true}")
    private boolean enableNotifications;

    @Bean
    public FeatureFlags featureFlags() {
        final Map<String, Boolean> flags = new HashMap<>();
        flags.put("plaid", enablePlaid);
        flags.put("stripe", enableStripe);
        flags.put("oauth2", enableOAuth2);
        flags.put("advanced-analytics", enableAdvancedAnalytics);
        flags.put("notifications", enableNotifications);
        return new FeatureFlags(flags);
    }

    public static class FeatureFlags {
        private final Map<String, Boolean> flags;

        public FeatureFlags(final Map<String, Boolean> flags) {
            this.flags = flags;
        }

        public boolean isEnabled(final String feature) {
            return flags.getOrDefault(feature, false);
        }

        public Map<String, Boolean> getAllFlags() {
            return new HashMap<>(flags);
        }
    }
}
