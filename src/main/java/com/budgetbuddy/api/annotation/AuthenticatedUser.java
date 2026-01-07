package com.budgetbuddy.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to inject the authenticated UserTable entity into controller methods
 * Must be used in conjunction with @RequireAuthenticatedUser on the method
 * 
 * The UserTable will be automatically loaded and injected by AuthenticationAspect
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedUser {
}

