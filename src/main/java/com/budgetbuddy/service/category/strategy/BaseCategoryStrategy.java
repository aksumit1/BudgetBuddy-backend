package com.budgetbuddy.service.category.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base class for category detection strategies Provides common utility methods */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public abstract class BaseCategoryStrategy implements CategoryDetectionStrategy {
    protected static final Logger LOGGER = LoggerFactory.getLogger(BaseCategoryStrategy.class);

    /** Check if any of the patterns match in the normalized text */
    protected boolean containsAny(final String text, final String... patterns) {
        if (text == null) {
            return false;
        }
        for (final String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Check if text starts with any pattern */
    protected boolean startsWithAny(final String text, final String... patterns) {
        if (text == null) {
            return false;
        }
        for (final String pattern : patterns) {
            if (text.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Check if text ends with any pattern */
    protected boolean endsWithAny(final String text, final String... patterns) {
        if (text == null) {
            return false;
        }
        for (final String pattern : patterns) {
            if (text.endsWith(pattern)) {
                return true;
            }
        }
        return false;
    }
}
