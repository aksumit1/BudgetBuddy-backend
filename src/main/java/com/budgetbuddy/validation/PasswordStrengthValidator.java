package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Password strength validator implementation
 * Validates password meets security requirements:
 * - At least 8 characters
 * - Contains uppercase letter
 * - Contains lowercase letter
 * - Contains digit
 * - Contains special character
 */
@Component
public class PasswordStrengthValidator implements ConstraintValidator<StrongPassword, String> {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$"
    );

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    @Override
    public void initialize(final StrongPassword constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(final String password, final ConstraintValidatorContext context) {
        if (password == null || password.isEmpty()) {
            return false;
        }

        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            return false;
        }

        return PASSWORD_PATTERN.matcher(password).matches();
    }
}

