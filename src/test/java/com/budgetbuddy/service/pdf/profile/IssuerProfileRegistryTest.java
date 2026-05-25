package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the registry's two remaining contracts: every input gets a
 * non-null profile back (so callers never NPE), and the priority chain
 * routes to the first profile whose {@code matches} predicate fires.
 *
 * <p>The default registry deliberately ships an EMPTY chain — every
 * supported issuer migrated to YAML v2 templates living elsewhere
 * (see {@link IssuerProfileRegistry#defaultRegistry()} Javadoc) — so
 * detection-specific coverage now lives with the v2 template tests.
 * What remains testable here is the chain-walking logic itself, which
 * we exercise with synthetic profiles.
 */
final class IssuerProfileRegistryTest {

    @Test
    void defaultRegistry_returnsGenericFallback_forAnyInput() {
        final IssuerProfileRegistry registry = IssuerProfileRegistry.defaultRegistry();
        final IssuerProfile p = registry.detect("Chase Sapphire");
        assertNotNull(p);
        assertEquals("generic", p.issuerId());
        assertTrue(p instanceof GenericFallbackProfile);
    }

    @Test
    void defaultRegistry_returnsFallback_forNullHeader() {
        final IssuerProfile p = IssuerProfileRegistry.defaultRegistry().detect(null);
        assertNotNull(p);
        assertEquals("generic", p.issuerId());
    }

    @Test
    void defaultRegistry_hasEmptyChain() {
        final List<IssuerProfile> profiles =
                IssuerProfileRegistry.defaultRegistry().registeredProfiles();
        assertEquals(
                0,
                profiles.size(),
                "All issuer detection moved to YAML v2; legacy chain stays empty.");
    }

    @Test
    void detect_returnsFirstMatchingProfile_inPriorityOrder() {
        final IssuerProfile high = new StubProfile("high", text -> text.contains("HIGH"));
        final IssuerProfile low = new StubProfile("low", text -> text.contains("LOW"));
        final IssuerProfileRegistry registry =
                new IssuerProfileRegistry(List.of(high, low));

        assertSame(high, registry.detect("only HIGH matches"));
        assertSame(low, registry.detect("only LOW matches"));
    }

    @Test
    void detect_returnsHigherPriorityProfile_whenMultipleMatch() {
        // Order matters: a more-specific profile registered first must
        // win even if a broader profile would also match.
        final IssuerProfile specific = new StubProfile("specific", text -> text.contains("FOO"));
        final IssuerProfile broad = new StubProfile("broad", text -> true);
        final IssuerProfileRegistry registry =
                new IssuerProfileRegistry(List.of(specific, broad));

        assertSame(specific, registry.detect("FOO appears here"));
        assertSame(broad, registry.detect("no foo here at all"));
    }

    @Test
    void detect_returnsFallback_whenNoProfileMatches() {
        final IssuerProfile noMatch = new StubProfile("never", text -> false);
        final IssuerProfileRegistry registry =
                new IssuerProfileRegistry(List.of(noMatch));

        final IssuerProfile p = registry.detect("anything");
        assertEquals("generic", p.issuerId());
    }

    @Test
    void profileFor_returnsRegisteredProfileById_caseInsensitive() {
        final IssuerProfile alpha = new StubProfile("alpha", text -> false);
        final IssuerProfileRegistry registry =
                new IssuerProfileRegistry(List.of(alpha));

        assertSame(alpha, registry.profileFor("alpha"));
        assertSame(alpha, registry.profileFor("ALPHA"));
    }

    @Test
    void profileFor_returnsNull_forUnknownOrNullId() {
        final IssuerProfileRegistry registry = IssuerProfileRegistry.defaultRegistry();
        assertNull(registry.profileFor("nonexistent"));
        assertNull(registry.profileFor(null));
    }

    @Test
    void registeredProfiles_doesNotIncludeFallback() {
        final IssuerProfile a = new StubProfile("a", text -> false);
        final IssuerProfileRegistry registry =
                new IssuerProfileRegistry(List.of(a));

        final List<IssuerProfile> profiles = registry.registeredProfiles();
        assertEquals(1, profiles.size());
        assertSame(a, profiles.get(0));
    }

    @Test
    void fallbackProfile_isStableSingleton_perRegistry() {
        final IssuerProfileRegistry registry = IssuerProfileRegistry.defaultRegistry();
        assertSame(registry.fallbackProfile(), registry.fallbackProfile());
    }

    /**
     * Minimal IssuerProfile for chain-walking assertions. issuerId() is
     * final in AbstractIssuerProfile so we pass it through the
     * constructor; only matches() needs overriding.
     */
    private static final class StubProfile extends AbstractIssuerProfile {
        private final java.util.function.Predicate<String> predicate;

        StubProfile(final String id, final java.util.function.Predicate<String> predicate) {
            super(id, id, java.util.regex.Pattern.compile("^$"), java.util.Map.of());
            this.predicate = predicate;
        }

        @Override
        public boolean matches(final String headerText) {
            return headerText != null && predicate.test(headerText);
        }
    }
}
