package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Amount validator implementation Validates amount is positive and within reasonable limits */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class AmountValidator implements ConstraintValidator<ValidAmount, Double> {

    private static final BigDecimal MIN_AMOUNT = BigDecimal.ZERO;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    private static final int MAX_DECIMAL_PLACES = 2;

    @Override
    public void initialize(final ValidAmount constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(final Double amount, final ConstraintValidatorContext context) {
        if (amount == null) {
            return false;
        }

        try {
            final BigDecimal amountDecimal =
                    BigDecimal.valueOf(amount).setScale(MAX_DECIMAL_PLACES, RoundingMode.HALF_UP);

            return amountDecimal.compareTo(MIN_AMOUNT) > 0
                    && amountDecimal.compareTo(MAX_AMOUNT) <= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
