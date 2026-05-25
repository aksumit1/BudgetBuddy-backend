package com.budgetbuddy.config;

import com.budgetbuddy.api.resolver.AuthenticatedUserArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration Registers interceptors for API versioning, logging, etc. Registers argument
 * resolvers for custom parameter injection.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiVersioningInterceptor apiVersioningInterceptor;
    private final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver;
    private final ObjectMapper objectMapper;

    public WebMvcConfig(
            final ApiVersioningInterceptor apiVersioningInterceptor,
            final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver,
            final ObjectMapper objectMapper) {
        this.apiVersioningInterceptor = apiVersioningInterceptor;
        this.authenticatedUserArgumentResolver = authenticatedUserArgumentResolver;
        this.objectMapper = objectMapper;
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

    /**
     * Spring Boot 4 / Spring Framework 7 switched the default HTTP message converter to
     * Jackson 3 ({@code tools.jackson.*}). Our codebase is built on Jackson 2
     * ({@code com.fasterxml.jackson.*}) — especially the MCP server, which exchanges
     * {@code JsonNode} payloads via {@code @RequestBody}, and {@link
     * com.budgetbuddy.api.response.ApiResponseWrappingAdvice}, which gates the
     * {@code {status,data,…}} envelope on {@code AbstractJackson2HttpMessageConverter}.
     * Inserting the legacy Jackson 2 converter at the head of the list keeps both paths
     * working without a wholesale Jackson 3 migration.
     *
     * <p>Using {@code extendMessageConverters} rather than {@code configureMessageConverters}
     * so Spring still autoregisters the rest of the chain (StringHttpMessageConverter,
     * ByteArrayHttpMessageConverter, ResourceHttpMessageConverter, etc).
     */
    @Override
    public void extendMessageConverters(final List<HttpMessageConverter<?>> converters) {
        converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper));
    }
}
