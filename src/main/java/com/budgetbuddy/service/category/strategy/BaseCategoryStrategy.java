package com.budgetbuddy.service.category.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for category detection strategies
 * Provides common utility methods
 */
public abstract class BaseCategoryStrategy implements CategoryDetectionStrategy {
    protected static final Logger logger = LoggerFactory.getLogger(BaseCategoryStrategy.class);
    
    /**
     * Check if any of the patterns match in the normalized text
     */
    protected boolean containsAny(String text, String... patterns) {
        if (text == null) return false;
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if text starts with any pattern
     */
    protected boolean startsWithAny(String text, String... patterns) {
        if (text == null) return false;
        for (String pattern : patterns) {
            if (text.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if text ends with any pattern
     */
    protected boolean endsWithAny(String text, String... patterns) {
        if (text == null) return false;
        for (String pattern : patterns) {
            if (text.endsWith(pattern)) {
                return true;
            }
        }
        return false;
    }
}
