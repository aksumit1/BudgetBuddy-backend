package com.budgetbuddy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Arrays;
import java.util.Locale;

/**
 * Internationalization (i18n) Configuration
 * Supports multiple languages for enterprise global deployment
 */
@Configuration
public class InternationalizationConfig {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.US);
        resolver.setSupportedLocales(Arrays.asList(
                Locale.US,      // English (US)
                Locale.UK,      // English (UK)
                Locale.CANADA,  // English (Canada)
                Locale.FRANCE,  // French
                Locale.GERMANY, // German
                Locale.ITALY,   // Italian
                Locale.SPAIN,   // Spanish
                Locale.JAPAN,   // Japanese
                Locale.CHINA,   // Chinese (Simplified)
                Locale.KOREA,   // Korean
                new Locale("pt", "BR"), // Portuguese (Brazil)
                new Locale("ru", "RU"), // Russian
                new Locale("ar", "SA"), // Arabic
                new Locale("hi", "IN")  // Hindi
        ));
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Bean
    public ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}

