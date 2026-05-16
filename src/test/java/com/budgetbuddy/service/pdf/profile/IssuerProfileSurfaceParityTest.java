package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Structural test that enforces full surface parity between the deprecated
 * {@link StatementParsingUtilities} façade and the {@link IssuerProfile} contract.
 *
 * <p>The migration story only ends when every {@code extract*} method exposed by
 * the legacy façade has a corresponding {@code extract*} method on the profile
 * interface. Until then, callers that need a "legacy-only" extractor (e.g. annual
 * fee, foreign-tx fee, Chase Freedom next-quarter bonus) must reach in to the
 * deprecated façade — which defeats the purpose of the architecture.
 *
 * <p>This test asserts that every {@code extract*} method on {@link
 * StatementParsingUtilities} is mirrored on {@link IssuerProfile}, by name. Any new
 * method added to legacy without a matching profile method fails this test loudly.
 */
class IssuerProfileSurfaceParityTest {

    @Test
    void everyLegacyExtractMethod_hasMatchingMethodOnIssuerProfile() {
        // Collect all extract* method names from the legacy façade.
        final Set<String> legacyMethodNames = new HashSet<>();
        for (final Method m : StatementParsingUtilities.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!m.getName().startsWith("extract")) {
                continue;
            }
            legacyMethodNames.add(m.getName());
        }

        // Collect all extract* method names from the profile interface.
        final Set<String> profileMethodNames = new HashSet<>();
        for (final Method m : IssuerProfile.class.getDeclaredMethods()) {
            if (m.getName().startsWith("extract")) {
                profileMethodNames.add(m.getName());
            }
        }

        // Diff: anything in legacy but not in profile is a gap.
        final Set<String> missing = new HashSet<>(legacyMethodNames);
        missing.removeAll(profileMethodNames);
        if (!missing.isEmpty()) {
            fail(
                    "These legacy extract methods are NOT on IssuerProfile — "
                            + "callers can only reach them via the deprecated façade:\n  "
                            + String.join("\n  ", missing.stream().sorted().toList())
                            + "\nAdd matching default methods to IssuerProfile + "
                            + "delegations on AbstractIssuerProfile so the legacy "
                            + "façade can be fully removed.");
        }
    }

    @Test
    void issuerProfile_exposesAtLeastTheLegacyBreadth() {
        // Sanity floor: at least 30 extract methods on the profile interface.
        final long count = Arrays.stream(IssuerProfile.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("extract"))
                .count();
        assertTrue(count >= 30,
                "IssuerProfile contract is expected to expose at least 30 "
                        + "extract* methods (got " + count + "). If you removed "
                        + "methods intentionally, lower this floor.");
    }

    @Test
    void abstractIssuerProfile_overridesEveryProfileMethod() {
        // Defense in depth: AbstractIssuerProfile must override every default method
        // on the interface so subclasses inherit the legacy-delegate behavior. If a
        // method is left as the interface default (returns null), profiles that don't
        // override it will silently miss extraction. Pin the count so any future
        // addition forces an explicit override here.
        final Set<String> interfaceMethods = new HashSet<>();
        for (final Method m : IssuerProfile.class.getDeclaredMethods()) {
            if (m.getName().startsWith("extract") && m.isDefault()) {
                interfaceMethods.add(m.getName());
            }
        }
        final Set<String> abstractOverrides = new HashSet<>();
        for (final Method m : AbstractIssuerProfile.class.getDeclaredMethods()) {
            if (m.getName().startsWith("extract")) {
                abstractOverrides.add(m.getName());
            }
        }
        final Set<String> notOverridden = new HashSet<>(interfaceMethods);
        notOverridden.removeAll(abstractOverrides);
        if (!notOverridden.isEmpty()) {
            fail(
                    "AbstractIssuerProfile is missing overrides for these IssuerProfile "
                            + "methods (subclasses will inherit the no-op default):\n  "
                            + String.join("\n  ", notOverridden.stream().sorted().toList()));
        }
    }

    @Test
    void sharedUtilityClass_isNotDeprecated() {
        // StatementParsingUtilities is the canonical home for cross-issuer regex
        // helpers + Pattern unions. It is NOT a deprecated/legacy class — it's a
        // first-class part of the profile architecture. If you re-add @Deprecated
        // here, you're signalling the wrong story to future contributors.
        assertEquals(null, StatementParsingUtilities.class.getAnnotation(Deprecated.class),
                "StatementParsingUtilities must NOT be @Deprecated");
    }
}
