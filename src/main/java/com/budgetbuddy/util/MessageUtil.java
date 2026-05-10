package com.budgetbuddy.util;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Message Utility for Internationalization Provides localized messages based on user locale
 *
 * <p>Features: - Locale-aware message retrieval - Error message formatting - Success message
 * formatting - Validation message formatting
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Component
public class MessageUtil {

    private final MessageSource messageSource;

    public MessageUtil(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Get localized message */
    public String getMessage(final String code) {
        return getMessage(code, null);
    }

    /** Get localized message with arguments */
    public String getMessage(final String code, final Object[] args) {
        final Locale locale = LocaleContextHolder.getLocale();
        return getMessage(code, args, locale);
    }

    /** Get localized message with specific locale */
    public String getMessage(final String code, final Object[] args, final Locale locale) {
        try {
            return messageSource.getMessage(code, args, code, locale);
        } catch (Exception e) {
            // Return code if message not found
            return code;
        }
    }

    /** Get error message */
    public String getErrorMessage(final String errorCode) {
        final String messageKey = "error." + errorCode.toLowerCase(Locale.ROOT).replace("_", ".");
        return getMessage(messageKey);
    }

    /** Get success message */
    public String getSuccessMessage(final String successCode) {
        final String messageKey = "success." + successCode.toLowerCase(Locale.ROOT).replace("_", ".");
        return getMessage(messageKey);
    }

    /** Get validation message */
    public String getValidationMessage(final String validationCode) {
        final String messageKey = "validation." + validationCode.toLowerCase(Locale.ROOT).replace("_", ".");
        return getMessage(messageKey);
    }
}
