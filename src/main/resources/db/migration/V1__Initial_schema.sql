-- BudgetBuddy Backend Database Schema
-- Version 1: Initial schema creation

-- Users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone_number VARCHAR(20),
    enabled BOOLEAN NOT NULL DEFAULT true,
    email_verified BOOLEAN NOT NULL DEFAULT false,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT false,
    preferred_currency VARCHAR(3) DEFAULT 'USD',
    timezone VARCHAR(10) DEFAULT 'UTC',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP,
    password_changed_at TIMESTAMP
);

CREATE INDEX idx_user_email ON users(email);
CREATE INDEX idx_user_created ON users(created_at);

-- User roles table
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- Accounts table
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_name VARCHAR(255) NOT NULL,
    institution_name VARCHAR(255),
    account_type VARCHAR(50),
    account_subtype VARCHAR(50),
    balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    currency_code VARCHAR(3) DEFAULT 'USD',
    plaid_account_id VARCHAR(255),
    plaid_item_id VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT true,
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_user ON accounts(user_id);
CREATE INDEX idx_account_plaid ON accounts(plaid_account_id);
CREATE INDEX idx_account_type ON accounts(account_type);

-- Transactions table
CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    amount DECIMAL(19, 2) NOT NULL,
    description VARCHAR(255),
    merchant_name VARCHAR(100),
    category VARCHAR(50),
    transaction_date DATE NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'USD',
    plaid_transaction_id VARCHAR(255),
    pending BOOLEAN DEFAULT false,
    location TEXT,
    payment_channel VARCHAR(50),
    authorized_date VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transaction_user ON transactions(user_id);
CREATE INDEX idx_transaction_account ON transactions(account_id);
CREATE INDEX idx_transaction_date ON transactions(transaction_date);
CREATE INDEX idx_transaction_plaid ON transactions(plaid_transaction_id);
CREATE INDEX idx_transaction_category ON transactions(category);

-- Budgets table
CREATE TABLE budgets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(50) NOT NULL,
    monthly_limit DECIMAL(19, 2) NOT NULL,
    current_spent DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    currency_code VARCHAR(3) DEFAULT 'USD',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, category)
);

CREATE INDEX idx_budget_user ON budgets(user_id);
CREATE INDEX idx_budget_category ON budgets(category);

-- Goals table
CREATE TABLE goals (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    target_amount DECIMAL(19, 2) NOT NULL,
    current_amount DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    target_date DATE NOT NULL,
    monthly_contribution DECIMAL(19, 2),
    goal_type VARCHAR(50),
    currency_code VARCHAR(3) DEFAULT 'USD',
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_goal_user ON goals(user_id);
CREATE INDEX idx_goal_target_date ON goals(target_date);

-- Audit log table for compliance
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(255),
    details JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_user ON audit_logs(user_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_resource ON audit_logs(resource_type, resource_id);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

-- Analytics aggregation table
CREATE TABLE analytics_aggregations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    aggregation_type VARCHAR(50) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    metrics JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, aggregation_type, period_start, period_end)
);

CREATE INDEX idx_analytics_user ON analytics_aggregations(user_id);
CREATE INDEX idx_analytics_type ON analytics_aggregations(aggregation_type);
CREATE INDEX idx_analytics_period ON analytics_aggregations(period_start, period_end);

