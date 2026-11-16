package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Static knowledge source provider for development and testing.
 * <p>
 * <b>DEV ONLY</b> — Provides hardcoded sample help center, policy, and FAQ content.
 * In production, replace with a CMS-backed or database-backed implementation.
 */
@Component
@Profile({"dev", "test", "local"})
public class StaticKnowledgeSourceProvider implements KnowledgeSourceProvider {

    @Override
    public List<KnowledgeDocument> fetchAllDocuments() {
        return List.of(
                // Help Center Articles
                new KnowledgeDocument(
                        "help-order-tracking",
                        "How to Track Your Order",
                        "You can track your order in real-time using the Bhukkad app or website. " +
                        "Go to 'My Orders', select the order, and tap 'Track Order'. " +
                        "You'll see the rider's live location and estimated delivery time. " +
                        "Notifications are sent at key milestones: restaurant confirmed, food prepared, rider picked up, rider arriving.",
                        "help",
                        "https://help.bhukkad.com/order-tracking",
                        Map.of("tags", "order,tracking,delivery,rider")
                ),
                new KnowledgeDocument(
                        "help-refund-policy",
                        "Refund Policy",
                        "Bhukkad offers refunds for the following cases: " +
                        "1) Order cancelled by restaurant - full refund within 24 hours. " +
                        "2) Items missing from order - partial refund for missing items. " +
                        "3) Wrong items delivered - full refund for incorrect items. " +
                        "4) Quality issues (cold food, wrong preparation) - partial or full refund based on severity. " +
                        "Refunds are processed to the original payment method within 5-7 business days. " +
                        "Contact support with order ID and photos for quality issues.",
                        "policy",
                        "https://help.bhukkad.com/refund-policy",
                        Map.of("tags", "refund,policy,payment,cancellation")
                ),
                new KnowledgeDocument(
                        "help-payment-issues",
                        "Payment Failed or Charged Twice",
                        "If your payment failed but money was deducted: " +
                        "1) Wait 24 hours - most banks reverse failed transactions automatically. " +
                        "2) Check your bank statement for 'pending' charges. " +
                        "3) If still charged after 24 hours, contact support with transaction ID and screenshot. " +
                        "For double charges, we'll refund the duplicate within 3-5 business days. " +
                        "Always use the in-app payment methods for best protection.",
                        "help",
                        "https://help.bhukkad.com/payment-issues",
                        Map.of("tags", "payment,failed,double-charge,bank")
                ),
                new KnowledgeDocument(
                        "help-account-security",
                        "Account Security & Privacy",
                        "Protect your Bhukkad account: " +
                        "1) Enable 2FA in Account Settings. " +
                        "2) Never share OTP or password with anyone claiming to be support. " +
                        "3) Bhukkad staff will never ask for your password. " +
                        "4) Report suspicious activity immediately. " +
                        "We use encryption for all sensitive data and never store full card details. " +
                        "Your data is processed per our Privacy Policy.",
                        "policy",
                        "https://help.bhukkad.com/account-security",
                        Map.of("tags", "security,privacy,2fa,account")
                ),

                // FAQ
                new KnowledgeDocument(
                        "faq-delivery-time",
                        "FAQ: How long does delivery take?",
                        "Typical delivery time is 30-45 minutes from order confirmation. " +
                        "Factors affecting time: restaurant preparation (10-20 min), distance (5-15 min), " +
                        "traffic/weather conditions. Peak hours (12-2 PM, 7-9 PM) may add 10-15 minutes. " +
                        "You can see real-time ETA in the order tracking screen.",
                        "faq",
                        "https://help.bhukkad.com/faq#delivery-time",
                        Map.of("tags", "delivery,time,eta,peak-hours")
                ),
                new KnowledgeDocument(
                        "faq-cancel-order",
                        "FAQ: Can I cancel my order?",
                        "You can cancel within 2 minutes of placing the order at no charge. " +
                        "After 2 minutes, the restaurant may have started preparation. " +
                        "Cancellation after preparation starts may incur a fee (up to 50% of order value). " +
                        "To cancel: Go to My Orders > Select Order > Cancel Order. " +
                        "Refunds for cancelled orders process within 24 hours.",
                        "faq",
                        "https://help.bhukkad.com/faq#cancel-order",
                        Map.of("tags", "cancel,order,cancellation,fee,refund")
                ),
                new KnowledgeDocument(
                        "faq-dietary-restrictions",
                        "FAQ: Dietary Restrictions & Allergies",
                        "Filter restaurants by dietary tags: Vegetarian, Vegan, Gluten-Free, Halal, Jain. " +
                        "Always check individual item descriptions for allergen info. " +
                        "Use 'Special Instructions' to notify the restaurant of allergies. " +
                        "For severe allergies, we recommend calling the restaurant directly after ordering. " +
                        "Bhukkad is not liable for cross-contamination in restaurant kitchens.",
                        "faq",
                        "https://help.bhukkad.com/faq#dietary",
                        Map.of("tags", "dietary,allergy,vegan,vegetarian,gluten-free,halal,jain")
                ),
                new KnowledgeDocument(
                        "faq-rider-contact",
                        "FAQ: How to Contact Your Rider",
                        "You can call or chat with your rider directly from the order tracking screen. " +
                        "Tap the rider's name/icon to open contact options. " +
                        "Use this for delivery instructions (gate code, landmark, leave at door). " +
                        "Riders may not answer calls while driving - use chat for non-urgent messages. " +
                        "If rider is unresponsive for 5+ minutes, contact support for reassignment.",
                        "faq",
                        "https://help.bhukkad.com/faq#rider-contact",
                        Map.of("tags", "rider,contact,delivery,instructions")
                ),
                new KnowledgeDocument(
                        "faq-promocodes",
                        "FAQ: Promo Codes & Discounts",
                        "Apply promo codes at checkout before payment. " +
                        "Only one code per order. Codes are case-sensitive. " +
                        "Expired codes won't work. Some codes are: first-order only, " +
                        "restaurant-specific, minimum order value, or new-user only. " +
                        "Check 'Offers' section in app for current promotions. " +
                        "Referral credits apply automatically on eligible orders.",
                        "faq",
                        "https://help.bhukkad.com/faq#promocodes",
                        Map.of("tags", "promo,discount,coupon,referral,offers")
                )
        );
    }
}