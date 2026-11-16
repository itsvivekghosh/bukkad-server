package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Simple keyword-based intent classifier for development and testing.
 * <p>
 * <b>DEV ONLY</b> — Uses pattern matching for intent classification.
 * In production, replace with an ML-based classifier or LLM-based intent detection.
 */
@Component
@Profile({"dev", "test", "local"})
public class KeywordIntentClassifier implements IntentClassifier {

    // Intent keywords (case-insensitive)
    private static final Map<Intent, List<Pattern>> INTENT_PATTERNS = Map.of(
            Intent.ORDER_LOOKUP, List.of(
                    Pattern.compile("\\b(order|order\\s*(?:id|number|#|status|details?)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(where\\s+is\\s+my\\s+order|my\\s+order\\s+status)", Pattern.CASE_INSENSITIVE)
            ),
            Intent.ORDER_TRACKING, List.of(
                    Pattern.compile("\\b(track|tracking|delivery\\s+status|rider\\s+location|eta|arriving)", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(where\\s+is\\s+(?:my|the)\\s+(?:rider|driver|delivery))", Pattern.CASE_INSENSITIVE)
            ),
            Intent.REFUND_REQUEST, List.of(
                    Pattern.compile("\\b(refund|money\\s+back|charge\\s+back|dispute|wrong\\s+order|missing\\s+item|quality\\s+issue|cold\\s+food)", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(i\\s+want\\s+(?:a\\s+)?refund|refund\\s+please)", Pattern.CASE_INSENSITIVE)
            ),
            Intent.POLICY_QUESTION, List.of(
                    Pattern.compile("\\b(policy|policies|faq|help|how\\s+to|what\\s+is|explain|information)", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(cancel\\s+policy|delivery\\s+time|dietary|allergy|promo|discount|coupon)", Pattern.CASE_INSENSITIVE)
            ),
            Intent.HUMAN_HANDOFF, List.of(
                    Pattern.compile("\\b(human|agent|representative|person|real\\s+person|talk\\s+to\\s+someone|speak\\s+to\\s+(?:a\\s+)?(?:human|agent))", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(escalate|transfer|handoff|hand\\s+off)", Pattern.CASE_INSENSITIVE)
            ),
            Intent.FRAUD_REPORT, List.of(
                    Pattern.compile("\\b(fraud|scam|fake|stolen|suspicious|report\\s+fraud)", Pattern.CASE_INSENSITIVE)
            ),
            Intent.GENERAL_CHAT, List.of(
                    Pattern.compile("\\b(hello|hi|hey|thanks|thank\\s+you|bye|goodbye|good\\s+(?:morning|afternoon|evening))", Pattern.CASE_INSENSITIVE)
            )
    );

    @Override
    public Intent classify(String message, Map<String, Object> context) {
        if (message == null || message.isBlank()) {
            return Intent.UNKNOWN;
        }

        String lower = message.toLowerCase(Locale.ROOT);

        // Check for explicit human handoff first (highest priority)
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.HUMAN_HANDOFF))) {
            return Intent.HUMAN_HANDOFF;
        }

        // Check for fraud report
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.FRAUD_REPORT))) {
            return Intent.FRAUD_REPORT;
        }

        // Check for order tracking (more specific than order lookup)
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.ORDER_TRACKING))) {
            return Intent.ORDER_TRACKING;
        }

        // Check for order lookup
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.ORDER_LOOKUP))) {
            return Intent.ORDER_LOOKUP;
        }

        // Check for refund request
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.REFUND_REQUEST))) {
            return Intent.REFUND_REQUEST;
        }

        // Check for policy question
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.POLICY_QUESTION))) {
            return Intent.POLICY_QUESTION;
        }

        // Check for general chat
        if (matchesAny(lower, INTENT_PATTERNS.get(Intent.GENERAL_CHAT))) {
            return Intent.GENERAL_CHAT;
        }

        return Intent.UNKNOWN;
    }

    private boolean matchesAny(String text, List<Pattern> patterns) {
        if (patterns == null) return false;
        return patterns.stream().anyMatch(p -> p.matcher(text).find());
    }
}