package com.budgetbuddy.config;

import com.budgetbuddy.api.resolver.AuthenticatedUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC Configuration
 * Registers interceptors for API versioning, logging, etc.
 * Registers argument resolvers for custom parameter injection.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiVersioningInterceptor apiVersioningInterceptor;
    private final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver;

    public WebMvcConfig(
            final ApiVersioningInterceptor apiVersioningInterceptor,
            final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver) {
        this.apiVersioningInterceptor = apiVersioningInterceptor;
        this.authenticatedUserArgumentResolver = authenticatedUserArgumentResolver;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(apiVersioningInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**");
    }

    @Override
    public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authenticatedUserArgumentResolver);
    }
}

