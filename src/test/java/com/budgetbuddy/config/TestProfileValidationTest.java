package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Validation test to ensure all @SpringBootTest tests use @ActiveProfiles("test") This prevents
 * tests from polluting the main LocalStack instance
 */
class TestProfileValidationTest {

    @Test
    void testAllSpringBootTestsUseTestProfile() {
        final Reflections reflections = new Reflections("com.budgetbuddy", Scanners.TypesAnnotated);
        final Set<Class<?>> springBootTestClasses =
                reflections.getTypesAnnotatedWith(SpringBootTest.class);

        // Filter out classes that have @ActiveProfiles("test")
        final Set<Class<?>> missingTestProfile =
                springBootTestClasses.stream()
                        .filter(
                                clazz -> {
                                    // Check if class has @ActiveProfiles("test")
                                    final ActiveProfiles activeProfiles =
                                            clazz.getAnnotation(ActiveProfiles.class);
                                    if (activeProfiles != null) {
                                        for (final String profile : activeProfiles.value()) {
                                            if ("test".equals(profile)) {
                                                return false; // Has test profile, don't include
                                            }
                                        }
                                    }
                                    // Check if any parent class has @ActiveProfiles("test")
                                    Class<?> parent = clazz.getSuperclass();
                                    while (parent != null && parent != Object.class) {
                                        final ActiveProfiles parentProfiles =
                                                parent.getAnnotation(ActiveProfiles.class);
                                        if (parentProfiles != null) {
                                            for (final String profile : parentProfiles.value()) {
                                                if ("test".equals(profile)) {
                                                    return false; // Parent has test profile
                                                }
                                            }
                                        }
                                        parent = parent.getSuperclass();
                                    }
                                    return true; // Missing test profile
                                })
                        .collect(Collectors.toSet());

        if (!missingTestProfile.isEmpty()) {
            final StringBuilder message = new StringBuilder();
            message.append(
                    "The following test classes use @SpringBootTest but are missing @ActiveProfiles(\"test\"):\n");
            missingTestProfile.forEach(
                    clazz -> {
                        message.append("  - ").append(clazz.getName()).append("\n");
                    });
            message.append("\nThis can cause tests to pollute the main LocalStack instance.\n");
            message.append(
                    "Please add @ActiveProfiles(\"test\") and @Import(AWSTestConfiguration.class) to these classes.");

            fail(message.toString());
        }

        // If we get here, all tests have the test profile
        assertTrue(true, "All @SpringBootTest classes use @ActiveProfiles(\"test\")");
    }
}
