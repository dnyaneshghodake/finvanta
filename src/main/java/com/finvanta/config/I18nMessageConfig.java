package com.finvanta.config;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * CBS i18n Configuration per Finacle GLOBAL_LABEL / Temenos LANGUAGE.
 *
 * <p>Provides a {@link MessageSource} so service-layer error codes (e.g.
 * {@code TENANT_NOT_SET}, {@code REFRESH_TOKEN_REUSED}, {@code CHARGE_INACTIVE})
 * and JSP validation messages can be resolved against locale-specific bundles.
 * Falls back to {@code messages.properties} when no per-locale override exists,
 * so the English baseline continues to work unchanged.
 *
 * <p>Per RBI Fair Practices Code 2023 §3.1: customer-facing error / notification
 * strings SHOULD be deliverable in the account-holder's vernacular (Hindi /
 * regional language). This config is the wiring hook -- per-language bundles
 * (e.g. {@code messages_hi.properties}) can be added incrementally without
 * touching Java code.
 *
 * <p>Locale resolution strategy: honour the HTTP {@code Accept-Language} header
 * (Temenos EB.LOOKUP default), fall back to {@link Locale#ENGLISH} when the
 * request presents no recognised language. This matches the behaviour expected
 * by mobile-banking and IMPS clients that set explicit language tags.
 */
@Configuration
public class I18nMessageConfig {

    private static final String BASENAME = "classpath:messages/messages";
    private static final int CACHE_SECONDS = 3600;

    /**
     * Primary {@link MessageSource} used by {@code @ControllerAdvice} error
     * translation, validation messages, and any future {@code MessageSource}
     * injection in services. Uses {@code ReloadableResourceBundleMessageSource}
     * so ops can drop updated bundles on the classpath without a restart
     * (useful for emergency wording tweaks under RBI directives).
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename(BASENAME);
        ms.setDefaultEncoding("UTF-8");
        ms.setCacheSeconds(CACHE_SECONDS);
        // Return the message code itself when no bundle entry is found, so a
        // missing translation degrades to the canonical CBS error code rather
        // than a NoSuchMessageException at runtime. Operations teams can then
        // grep the log for the code and add the bundle entry incrementally.
        ms.setUseCodeAsDefaultMessage(true);
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    /**
     * {@link LocaleResolver} that honours the {@code Accept-Language} header.
     * Per Finacle GLOBAL_LABEL: each request's locale is resolved independently
     * (no session-level pinning) because CBS is often invoked by headless
     * integration clients (NPCI adapters, Account Aggregator FIU/FIP, ATM
     * switches) that may vary per call.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
