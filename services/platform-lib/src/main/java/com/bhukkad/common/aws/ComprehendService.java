package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.comprehend.model.*;

import java.util.List;

/**
 * AWS Comprehend service for natural language processing.
 * <p>
 * Provides natural language processing capabilities such as sentiment analysis,
 * entity recognition, key phrase extraction, and language detection.
 * <p>
 * Use cases in Bhukkad:
 * - Sentiment analysis on customer reviews
 *   <ul>
 *     <li>Fully testable against Floci emulator locally.</li>
 * </ul>
 */
@Service
public class ComprehendService {

    private final ComprehendClient comprehendClient;

    public ComprehendService(ComprehendClient comprehendClient) {
        this.comprehendClient = comprehendClient;
    }

    /**
     * Detects sentiment in text.
     * <p>
     * Example usage:
     * <pre>
     *   DetectSentimentResponse result = comprehendService.detectSentiment("I love this food!", "en");
     * </ul>
     */
    public DetectSentimentResponse detectSentiment(String text, String languageCode) {
        return comprehendClient.detectSentiment(DetectSentimentRequest.builder()
                .text(text)
                .languageCode(LanguageCode.fromValue(languageCode))
                .build());
    }

    /**
     * Detects entities in text.
     * <p>
     * Example usage:
     * <pre>
     *   DetectEntitiesResponse result = comprehendService.detectEntities("I love pizza", "en");
     * </ul>
     */
    public DetectEntitiesResponse detectEntities(String text, String languageCode) {
        return comprehendClient.detectEntities(DetectEntitiesRequest.builder()
                .text(text)
                .languageCode(LanguageCode.fromValue(languageCode))
                .build());
    }

    /**
     * Detects key phrases in text.
     */
    public DetectKeyPhrasesResponse detectKeyPhrases(String text, String languageCode) {
        return comprehendClient.detectKeyPhrases(DetectKeyPhrasesRequest.builder()
                .text(text)
                .languageCode(LanguageCode.fromValue(languageCode))
                .build());
    }

    /**
     * Detects the dominant language in text.
     */
    public DetectDominantLanguageResponse detectDominantLanguage(String text) {
        return comprehendClient.detectDominantLanguage(DetectDominantLanguageRequest.builder()
                .text(text)
                .build());
    }
}