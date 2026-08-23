package com.bhukkad.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.context.i18n.LocaleContextHolder;

@Service
@RequiredArgsConstructor
public class I18nService {

    private final MessageSource messageSource;

    public String getMessage(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }

    public String getMessage(String code, Object... args) {
        return messageSource.getMessage(code, args, code, LocaleContextHolder.getLocale());
    }

    public String resolve(String code, String fallback) {
        return messageSource.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }
}
