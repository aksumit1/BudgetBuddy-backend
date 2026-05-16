package com.budgetbuddy.service.pdf.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered chain of {@link IssuerProfile}s that classifies a statement to ONE profile.
 *
 * <p>Order matters: more specific profiles must come before broader ones to avoid
 * false-positive captures (e.g. a card co-branded by Citi that mentions both "Citi"
 * and the partner brand). When no issuer-specific profile claims the statement, the
 * registry returns {@link GenericFallbackProfile} so callers always get a non-null
 * profile back.
 *
 * <p>Thread-safe: the registry is constructed once at startup and the underlying list
 * is unmodifiable thereafter.
 */
public final class IssuerProfileRegistry {

    private final List<IssuerProfile> profiles;
    private final IssuerProfile fallback;

    /**
     * @param profiles in priority order. The fallback is appended automatically; do not
     *     include it in this list.
     */
    public IssuerProfileRegistry(final List<IssuerProfile> profiles) {
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        this.fallback = new GenericFallbackProfile();
    }

    /**
     * Returns the bundled default chain that covers every issuer the codebase
     * recognizes at the time of writing. Ordering rationale:
     *
     * <ol>
     *   <li>Apple Card first — most distinctive header (no overlap with other issuers).
     *   <li>BoA before Chase / Citi — "Bank of America" is unambiguous; other issuers
     *       don't mention BoA so this is collision-free.
     *   <li>Wells Fargo before Chase — Wells doesn't mention Chase but Chase
     *       co-branded cards (e.g. Marriott) mention partners that could fuzzy-match.
     *   <li>US Bank before Citi — distinct vocabularies; either order works.
     *   <li>Amex before Chase only because Amex's co-branded "Marriott Bonvoy
     *       Brilliant" overlaps with Chase's "Marriott Bonvoy Bold/Boundless" terms.
     *       Amex headers always say "American Express", Chase headers always say
     *       "chase.com" — both must match for safety.
     *   <li>Chase + Citi last among the issuer-specific profiles.
     * </ol>
     */
    public static IssuerProfileRegistry defaultRegistry() {
        // v2 YAML owns the migrated issuers (US Bank, Chase, Amex, Citi, Wells
        // Fargo). Only un-migrated issuers stay on the legacy chain.
        return new IssuerProfileRegistry(
                List.of(
                        new AppleCardIssuerProfile(),
                        new BankOfAmericaIssuerProfile(),
                        new DiscoverIssuerProfile()));
    }

    /** Returns the first profile whose {@code matches} predicate fires, or fallback. */
    public IssuerProfile detect(final String headerText) {
        if (headerText == null) {
            return fallback;
        }
        for (final IssuerProfile p : profiles) {
            if (p.matches(headerText)) {
                return p;
            }
        }
        return fallback;
    }

    /**
     * Returns the registered profile (excluding fallback) for the given issuerId, or
     * {@code null} when not found. Used by tests + diagnostics.
     */
    public IssuerProfile profileFor(final String issuerId) {
        if (issuerId == null) {
            return null;
        }
        for (final IssuerProfile p : profiles) {
            if (issuerId.equalsIgnoreCase(p.issuerId())) {
                return p;
            }
        }
        return null;
    }

    public List<IssuerProfile> registeredProfiles() {
        return profiles;
    }

    public IssuerProfile fallbackProfile() {
        return fallback;
    }
}
