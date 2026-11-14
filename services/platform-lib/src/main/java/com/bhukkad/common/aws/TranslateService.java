package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.*;

import java.util.List;

/**
 * AWS Translate service for language translation.
 * <p>
 * Provides text translation between languages.
 * <p>
 * Use cases in Bhukkad:
 * - Translate restaurant descriptions for international customers
 *   <ul>
 *     <li>Fully testable against Floci emulator locally.</li>
 * </ul>
 */
@Service
public class TranslateService {

    private final TranslateClient translateClient;

    public TranslateService(TranslateClient translateClient) {
        this.translateClient = translateClient;
    }

    /**
     * Translates text from one language to another.
     * <p>
     * Example usage:
     * <pre>
     *   TranslateTextResponse result = translateService.translateText("Hello", "en", "es");
     * </ul>
     */
    public TranslateTextResponse translateText(String text, String sourceLanguageCode, String targetLanguageCode) {
        return translateClient.translateText(TranslateTextRequest.builder()
                .text(text)
                .sourceLanguageCode(sourceLanguageCode)
                .targetLanguageCode(targetLanguageCode)
                .build());
    }

    /**
     * Translates text with automatic language detection.
     */
    public TranslateTextResponse translateTextAutoDetect(String text, String targetLanguageCode) {
        return translateClient.translateText(TranslateTextRequest.builder()
                .text(text)
                .sourceLanguageCode("auto")
                .targetLanguageCode(targetLanguageCode)
                .build());
    }
}