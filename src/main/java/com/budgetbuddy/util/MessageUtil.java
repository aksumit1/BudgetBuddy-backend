package com.budgetbuddy.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Message Utility for Internationalization
 * Provides localized messages based on user locale
 *
 * Features:
 * - Locale-aware message retrieval
 * - Error message formatting
 * - Success message formatting
 * - Validation message formatting
 */
@Component
public class MessageUtil {

    private final MessageSource messageSource;

    public MessageUtil(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Get localized message
     */
    public String getMessage((final String code) {
        return getMessage(code, null);
    }

    /**
     * Get localized message with arguments
     */
    public String getMessage((final String code, final Object[] args) {
        Locale locale = LocaleContextHolder.getLocale();
        return getMessage(code, args, locale);
    }

    /**
     * Get localized message with specific locale
     */
    public String getMessage((final String code, final Object[] args, final Locale locale) {
        try {
            return messageSource.getMessage(code, args, code, locale);
        } catch (Exception e) {
            // Return code if message not found
            return code;
        }
    }

    /**
     * Get error message
     */
    public String getErrorMessage((final String errorCode) {
        String messageKey = "error." + errorCode.toLowerCase().replace("_", ".");
        return getMessage(messageKey);
    }

    /**
     * Get success message
     */
    public String getSuccessMessage((final String successCode) {
        String messageKey = "success." + successCode.toLowerCase().replace("_", ".");
        return getMessage(messageKey);
    }

    /**
     * Get validation message
     */
    public String getValidationMessage((final String validationCode) {
        String messageKey = "validation." + validationCode.toLowerCase().replace("_", ".");
        return getMessage(messageKey);
    }
}
