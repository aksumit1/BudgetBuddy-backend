package com.budgetbuddy.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Amount validation annotation
 * Validates amount is positive and within reasonable limits
 */
@Documented
@Constraint(validatedBy = AmountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAmount {
    String message() default "Amount must be positive and less than 999,999,999.99";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

