package com.bhukkad.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nServiceTest {

    private I18nService i18nService;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        i18nService = new I18nService(messageSource);
    }

    @Test
    void getMessage_returnsEnglishByDefault() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            assertEquals("Welcome to Bhukkad", i18nService.getMessage("greeting"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void getMessage_returnsTranslatedValueForHindi() {
        LocaleContextHolder.setLocale(new Locale("hi"));
        try {
            String message = i18nService.getMessage("greeting");
            assertEquals("भुक्कड़ में आपका स्वागत है", message);
            assertTrue(i18nService.getMessage("error.generic").contains("अप्रत्याशित"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void getMessage_fallsBackToCodeForUnknownKey() {
        LocaleContextHolder.setLocale(new Locale("hi"));
        try {
            assertEquals("unknown.key", i18nService.getMessage("unknown.key"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void getMessage_returnsTranslatedValueForOtherLocales() {
        LocaleContextHolder.setLocale(new Locale("kn"));
        try {
            assertEquals("ಭುಕ್ಕಾಡ್‌ಗೆ ಸುಸ್ವಾಗತ", i18nService.getMessage("greeting"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void getMessage_withArgsFormatsParameters() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            String message = i18nService.getMessage("error.not_found", "order-123");
            assertEquals("Resource not found", message);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void resolve_returnsFallbackWhenKeyMissing() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            assertEquals("fallback text", i18nService.resolve("missing.key", "fallback text"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void resolve_returnsTranslatedValueWhenKeyPresent() {
        LocaleContextHolder.setLocale(new Locale("ta"));
        try {
            assertEquals("வளம் கிடைக்கவில்லை", i18nService.resolve("error.not_found", "not found"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
