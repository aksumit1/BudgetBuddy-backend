package com.budgetbuddy.api.util;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for consistent error handling across controllers
 * Provides standardized error handling patterns
 */
public final class ControllerErrorHandler {

    private ControllerErrorHandler() {
        // Utility class - prevent instantiation
    }

    /**
     * Standard error handling pattern for controller methods
     * Re-throws AppException, wraps other exceptions in AppException with INTERNAL_SERVER_ERROR
     * 
     * @param operation Description of the operation (for logging)
     * @param logger Logger instance for error logging
     * @param exception The exception to handle
     * @return AppException (never returns, always throws)
     * @throws AppException Always throws
     */
    public static AppException handleError(String operation, Logger logger, Exception exception) {
        if (exception instanceof AppException appException) {
            // Re-throw AppException as-is (already properly formatted)
            return appException;
        } else {
            // Wrap unexpected exceptions
            logger.error("Unexpected error during {}: {}", operation, exception.getMessage(), exception);
            return new AppException(ErrorCode.INTERNAL_SERVER_ERROR, 
                    "An unexpected error occurred during " + operation + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * Execute an operation with standard error handling
     * 
     * @param operation Description of the operation
     * @param logger Logger instance
     * @param operationCode The operation to execute
     * @return Result of the operation
     * @param <T> Return type
     * @throws AppException If operation fails
     */
    public static <T> T executeWithErrorHandling(String operation, Logger logger, 
                                                  java.util.function.Supplier<T> operationCode) {
        try {
            return operationCode.get();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw handleError(operation, logger, e);
        }
    }

    /**
     * Execute a void operation with standard error handling
     * 
     * @param operation Description of the operation
     * @param logger Logger instance
     * @param operationCode The operation to execute
     * @throws AppException If operation fails
     */
    public static void executeWithErrorHandling(String operation, Logger logger, 
                                                Runnable operationCode) {
        try {
            operationCode.run();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw handleError(operation, logger, e);
        }
    }
}

