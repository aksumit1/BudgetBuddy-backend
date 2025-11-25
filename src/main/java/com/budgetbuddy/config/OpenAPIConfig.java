package com.budgetbuddy.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 Configuration
 * Provides comprehensive API documentation following latest OpenAPI schema
 */
@Configuration
public class OpenAPIConfig {

    @Value("${app.api.version:1.0.0}")
    private String apiVersion;

    @Value("${app.api.base-url:https://api.budgetbuddy.com}")
    private String baseUrl;

    @Bean
    public OpenAPI budgetBuddyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BudgetBuddy Backend API")
                        .description("""
                                Enterprise-ready financial management API with comprehensive features:
                                
                                ## Features
                                - **Authentication & Authorization**: JWT-based authentication with OAuth2 support
                                - **Financial Data**: Plaid and Stripe integrations
                                - **Transactions**: Real-time transaction sync and webhook handling
                                - **Budgets**: Budget tracking and alerts
                                - **Goals**: Financial goal planning and tracking
                                - **Analytics**: Comprehensive financial analytics
                                - **Compliance**: PCI-DSS, SOC2, HIPAA, ISO27001 compliant
                                
                                ## Security
                                - All endpoints require authentication (except health check)
                                - Rate limiting: 100 requests/minute per user
                                - DDoS protection enabled
                                - Certificate pinning for MITM protection
                                
                                ## Compliance
                                - PCI-DSS compliant card data handling
                                - GDPR data export and deletion
                                - SOC 2 Type II controls
                                - HIPAA PHI protection
                                - ISO 27001 security management
                                """)
                        .version(apiVersion)
                        .contact(new Contact()
                                .name("BudgetBuddy API Support")
                                .email("api-support@budgetbuddy.com")
                                .url("https://budgetbuddy.com/support"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://budgetbuddy.com/license")))
                .servers(List.of(
                        new Server()
                                .url(baseUrl)
                                .description("Production Server"),
                        new Server()
                                .url("https://staging-api.budgetbuddy.com")
                                .description("Staging Server"),
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development Server")
                ))
                .tags(List.of(
                        new Tag().name("Authentication").description("User authentication and authorization"),
                        new Tag().name("Users").description("User management"),
                        new Tag().name("Accounts").description("Financial account management"),
                        new Tag().name("Transactions").description("Transaction management and sync"),
                        new Tag().name("Budgets").description("Budget tracking and management"),
                        new Tag().name("Goals").description("Financial goal planning"),
                        new Tag().name("Analytics").description("Financial analytics and insights"),
                        new Tag().name("Plaid").description("Plaid integration (link tokens, webhooks)"),
                        new Tag().name("Stripe").description("Stripe payment processing"),
                        new Tag().name("Compliance").description("Compliance and audit endpoints"),
                        new Tag().name("Notifications").description("Notification management")
                ));
    }
}

