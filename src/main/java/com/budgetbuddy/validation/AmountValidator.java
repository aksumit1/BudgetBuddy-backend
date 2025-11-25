package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amount validator implementation
 * Validates amount is positive and within reasonable limits
 */
@Component
public class AmountValidator implements ConstraintValidator<ValidAmount, Double> {
    
    private static final BigDecimal MIN_AMOUNT = BigDecimal.ZERO;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    private static final int MAX_DECIMAL_PLACES = 2;

    @Override
    public void initialize(ValidAmount constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(Double amount, ConstraintValidatorContext context) {
        if (amount == null) {
            return false;
        }
        
        try {
            BigDecimal amountDecimal = BigDecimal.valueOf(amount)
                    .setScale(MAX_DECIMAL_PLACES, RoundingMode.HALF_UP);
            
            return amountDecimal.compareTo(MIN_AMOUNT) > 0 && 
                   amountDecimal.compareTo(MAX_AMOUNT) <= 0;
        } catch (Exception e) {
            return false;
        }
    }
}

