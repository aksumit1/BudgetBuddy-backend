package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration
 * Registers interceptors for API versioning, logging, etc.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiVersioningInterceptor apiVersioningInterceptor;

    @Autowired
    public WebMvcConfig(final ApiVersioningInterceptor apiVersioningInterceptor) {
        this.apiVersioningInterceptor = apiVersioningInterceptor;
    }

    @Override
    public void addInterceptors((final InterceptorRegistry registry) {
        registry.addInterceptor(apiVersioningInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**");
    }
}

