package com.budgetbuddy.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Locale Configuration for Internationalization
 * Extracts locale from Accept-Language header or uses default
 */
@Configuration
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();

        // Set supported locales
        List<Locale> supportedLocales = Arrays.asList(
                Locale.US,      // en_US
                Locale.UK,      // en_GB
                Locale.FRANCE,  // fr_FR
                new Locale("es", "ES"), // es_ES
                Locale.GERMANY, // de_DE
                Locale.ITALY,   // it_IT
                Locale.JAPAN,   // ja_JP
                Locale.SIMPLIFIED_CHINESE, // zh_CN
                Locale.KOREA,   // ko_KR
                new Locale("pt", "BR"), // pt_BR
                new Locale("ru", "RU"), // ru_RU
                new Locale("ar", "SA"), // ar_SA
                new Locale("hi", "IN")  // hi_IN
        );

        resolver.setSupportedLocales(supportedLocales);

        // Set default locale
        resolver.setDefaultLocale(Locale.US);

        return resolver;
    }
}

