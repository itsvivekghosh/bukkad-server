package com.bhukkad.engagement.chat.ai;

import java.util.List;
import java.util.Map;

/**
 * Classifies user intent for routing to appropriate handlers.
 */
public interface IntentClassifier {

    /**
     * Classifies the user's message intent.
     *
     * @param message user's message
     * @param context conversation context (previous messages, user profile, etc.)
     * @return classified intent
     */
    Intent classify(String message, Map<String, Object> context);

    /**
     * User intent classification result.
     */
    enum Intent {
        ORDER_LOOKUP("Look up order details"),
        ORDER_TRACKING("Track order delivery status"),
        REFUND_REQUEST("Request refund or report issue"),
        POLICY_QUESTION("Ask about policies, FAQ, help"),
        GENERAL_CHAT("General conversation, greetings"),
        HUMAN_HANDOFF("Explicit request for human agent"),
        FRAUD_REPORT("Report suspected fraud"),
        UNKNOWN("Unrecognized intent");

        private final String description;

        Intent(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}