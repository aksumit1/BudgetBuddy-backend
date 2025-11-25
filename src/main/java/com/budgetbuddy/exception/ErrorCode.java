package com.budgetbuddy.exception;

/**
 * Comprehensive Error Code Enum
 * Categorizes all possible errors in the system
 */
public enum ErrorCode {
    // Authentication & Authorization (1xxx)
    AUTHENTICATION_FAILED(1001, "Authentication failed"),
    INVALID_CREDENTIALS(1002, "Invalid credentials"),
    TOKEN_EXPIRED(1003, "Token expired"),
    TOKEN_INVALID(1004, "Invalid token"),
    UNAUTHORIZED_ACCESS(1005, "Unauthorized access"),
    UNAUTHORIZED(1005, "Unauthorized access"), // Alias for UNAUTHORIZED_ACCESS
    INSUFFICIENT_PERMISSIONS(1006, "Insufficient permissions"),
    ACCOUNT_LOCKED(1007, "Account locked"),
    ACCOUNT_DISABLED(1008, "Account disabled"),
    MFA_REQUIRED(1009, "Multi-factor authentication required"),
    MFA_FAILED(1010, "Multi-factor authentication failed"),

    // User Management (2xxx)
    USER_NOT_FOUND(2001, "User not found"),
    USER_ALREADY_EXISTS(2002, "User already exists"),
    EMAIL_ALREADY_REGISTERED(2003, "Email already registered"),
    INVALID_EMAIL_FORMAT(2004, "Invalid email format"),
    PASSWORD_TOO_WEAK(2005, "Password does not meet strength requirements"),
    PASSWORD_RESET_FAILED(2006, "Password reset failed"),
    EMAIL_VERIFICATION_REQUIRED(2007, "Email verification required"),

    // Plaid Integration (3xxx)
    PLAID_CONNECTION_FAILED(3001, "Failed to connect to Plaid"),
    PLAID_INVALID_CREDENTIALS(3002, "Invalid Plaid credentials"),
    PLAID_ITEM_ERROR(3003, "Plaid item error"),
    PLAID_RATE_LIMIT_EXCEEDED(3004, "Plaid rate limit exceeded"),
    PLAID_INSTITUTION_ERROR(3005, "Plaid institution error"),
    PLAID_ACCOUNT_ERROR(3006, "Plaid account error"),
    PLAID_TRANSACTION_ERROR(3007, "Plaid transaction error"),
    PLAID_WEBHOOK_ERROR(3008, "Plaid webhook error"),

    // Stripe Integration (4xxx)
    STRIPE_CONNECTION_FAILED(4001, "Failed to connect to Stripe"),
    STRIPE_INVALID_API_KEY(4002, "Invalid Stripe API key"),
    STRIPE_PAYMENT_FAILED(4003, "Payment processing failed"),
    STRIPE_CARD_DECLINED(4004, "Card declined"),
    STRIPE_INSUFFICIENT_FUNDS(4005, "Insufficient funds"),
    STRIPE_INVALID_CARD(4006, "Invalid card details"),
    STRIPE_RATE_LIMIT_EXCEEDED(4007, "Stripe rate limit exceeded"),
    STRIPE_WEBHOOK_ERROR(4008, "Stripe webhook error"),

    // Data Validation (5xxx)
    INVALID_INPUT(5001, "Invalid input data"),
    MISSING_REQUIRED_FIELD(5002, "Missing required field"),
    INVALID_FORMAT(5003, "Invalid data format"),
    VALUE_OUT_OF_RANGE(5004, "Value out of acceptable range"),
    INVALID_DATE_RANGE(5005, "Invalid date range"),

    // Database & Storage (6xxx)
    DATABASE_CONNECTION_FAILED(6001, "Database connection failed"),
    DATABASE_QUERY_FAILED(6002, "Database query failed"),
    RECORD_NOT_FOUND(6003, "Record not found"),
    ACCOUNT_NOT_FOUND(6003, "Account not found"), // Alias for RECORD_NOT_FOUND
    TRANSACTION_NOT_FOUND(6003, "Transaction not found"), // Alias for RECORD_NOT_FOUND
    RECORD_ALREADY_EXISTS(6004, "Record already exists"),
    TRANSACTION_FAILED(6005, "Database transaction failed"),
    STORAGE_ERROR(6006, "Storage error"),

    // Network & External Services (7xxx)
    NETWORK_ERROR(7001, "Network error"),
    SERVICE_UNAVAILABLE(7002, "Service unavailable"),
    TIMEOUT_ERROR(7003, "Request timeout"),
    RATE_LIMIT_EXCEEDED(7004, "Rate limit exceeded"),
    EXTERNAL_API_ERROR(7005, "External API error"),

    // Compliance & Security (8xxx)
    PCI_DSS_VIOLATION(8001, "PCI-DSS compliance violation"),
    ENCRYPTION_FAILED(8002, "Encryption failed"),
    DECRYPTION_FAILED(8003, "Decryption failed"),
    SECURITY_VIOLATION(8004, "Security violation detected"),
    AUDIT_LOG_FAILED(8005, "Failed to create audit log"),
    COMPLIANCE_CHECK_FAILED(8006, "Compliance check failed"),

    // Business Logic (9xxx)
    INSUFFICIENT_BALANCE(9001, "Insufficient balance"),
    TRANSACTION_LIMIT_EXCEEDED(9002, "Transaction limit exceeded"),
    BUDGET_EXCEEDED(9003, "Budget exceeded"),
    BUDGET_NOT_FOUND(9003, "Budget not found"), // Alias for BUDGET_EXCEEDED
    GOAL_NOT_ACHIEVABLE(9004, "Goal not achievable"),
    GOAL_NOT_FOUND(9004, "Goal not found"), // Alias for GOAL_NOT_ACHIEVABLE
    INVALID_TRANSACTION(9005, "Invalid transaction"),

    // System Errors (10xxx)
    INTERNAL_SERVER_ERROR(10001, "Internal server error"),
    SERVICE_UNAVAILABLE_ERROR(10002, "Service temporarily unavailable"),
    CONFIGURATION_ERROR(10003, "Configuration error"),
    UNKNOWN_ERROR(10004, "Unknown error occurred");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static ErrorCode fromCode(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return UNKNOWN_ERROR;
    }
}

