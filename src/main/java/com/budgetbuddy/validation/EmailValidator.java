package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Email validator implementation
 * Uses RFC 5322 compliant email pattern
 */
@Component
public class EmailValidator implements ConstraintValidator<ValidEmail, String> {

    // RFC 5322 compliant email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    private static final int MAX_EMAIL_LENGTH = 254; // RFC 5321 limit

    @Override
    public void initialize(final ValidEmail constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(final String email, final ConstraintValidatorContext context) {
        if (email == null || email.isEmpty()) {
            return false;
        }

        if (email.length() > MAX_EMAIL_LENGTH) {
            return false;
        }

        return EMAIL_PATTERN.matcher(email).matches();
    }
}

