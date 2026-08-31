package com.bhukkad.security;

import com.bhukkad.entity.Admin;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import org.hibernate.Hibernate;

/**
 * Typed accessors for credentials and profile PII on {@link User}-typed
 * references. Since V62 those columns live on the per-role tables
 * (customers / restaurant_owners / delivery_agents / admins), not on the
 * shared registry row, so code holding a {@code User} reference resolves
 * them through this helper instead of calling the accessor directly.
 *
 * <p>Hibernate proxies are unwrapped before the type switch, so the helper
 * is safe on lazily-loaded associations as well as on entities returned
 * directly from repositories.</p>
 */
public final class AccountFields {

    private AccountFields() {
    }

    public static String email(User user) {
        User target = unproxy(user);
        if (target instanceof Customer c) return c.getEmail();
        if (target instanceof RestaurantOwner o) return o.getEmail();
        if (target instanceof DeliveryAgent a) return a.getEmail();
        if (target instanceof Admin ad) return ad.getEmail();
        return null;
    }

    public static void setEmail(User user, String email) {
        User target = unproxy(user);
        if (target instanceof Customer c) c.setEmail(email);
        else if (target instanceof RestaurantOwner o) o.setEmail(email);
        else if (target instanceof DeliveryAgent a) a.setEmail(email);
        else if (target instanceof Admin ad) ad.setEmail(email);
    }

    public static String password(User user) {
        User target = unproxy(user);
        if (target instanceof Customer c) return c.getPassword();
        if (target instanceof RestaurantOwner o) return o.getPassword();
        if (target instanceof DeliveryAgent a) return a.getPassword();
        if (target instanceof Admin ad) return ad.getPassword();
        return null;
    }

    public static void setPassword(User user, String password) {
        User target = unproxy(user);
        if (target instanceof Customer c) c.setPassword(password);
        else if (target instanceof RestaurantOwner o) o.setPassword(password);
        else if (target instanceof DeliveryAgent a) a.setPassword(password);
        else if (target instanceof Admin ad) ad.setPassword(password);
    }

    public static String fullName(User user) {
        User target = unproxy(user);
        if (target instanceof Customer c) return c.getFullName();
        if (target instanceof RestaurantOwner o) return o.getFullName();
        if (target instanceof DeliveryAgent a) return a.getFullName();
        if (target instanceof Admin ad) return ad.getFullName();
        return null;
    }

    public static void setFullName(User user, String fullName) {
        User target = unproxy(user);
        if (target instanceof Customer c) c.setFullName(fullName);
        else if (target instanceof RestaurantOwner o) o.setFullName(fullName);
        else if (target instanceof DeliveryAgent a) a.setFullName(fullName);
        else if (target instanceof Admin ad) ad.setFullName(fullName);
    }

    public static String phoneNumber(User user) {
        User target = unproxy(user);
        if (target instanceof Customer c) return c.getPhoneNumber();
        if (target instanceof RestaurantOwner o) return o.getPhoneNumber();
        if (target instanceof DeliveryAgent a) return a.getPhoneNumber();
        if (target instanceof Admin ad) return ad.getPhoneNumber();
        return null;
    }

    public static void setPhoneNumber(User user, String phoneNumber) {
        User target = unproxy(user);
        if (target instanceof Customer c) c.setPhoneNumber(phoneNumber);
        else if (target instanceof RestaurantOwner o) o.setPhoneNumber(phoneNumber);
        else if (target instanceof DeliveryAgent a) a.setPhoneNumber(phoneNumber);
        else if (target instanceof Admin ad) ad.setPhoneNumber(phoneNumber);
    }

    public static String profileImageUrl(User user) {
        User target = unproxy(user);
        if (target instanceof Customer c) return c.getProfileImageUrl();
        if (target instanceof RestaurantOwner o) return o.getProfileImageUrl();
        if (target instanceof DeliveryAgent a) return a.getProfileImageUrl();
        if (target instanceof Admin ad) return ad.getProfileImageUrl();
        return null;
    }

    public static void setProfileImageUrl(User user, String profileImageUrl) {
        User target = unproxy(user);
        if (target instanceof Customer c) c.setProfileImageUrl(profileImageUrl);
        else if (target instanceof RestaurantOwner o) o.setProfileImageUrl(profileImageUrl);
        else if (target instanceof DeliveryAgent a) a.setProfileImageUrl(profileImageUrl);
        else if (target instanceof Admin ad) ad.setProfileImageUrl(profileImageUrl);
    }

    public static String totpSecret(User user) {
        User target = unproxy(user);
        if (target instanceof Customer c) return c.getTotpSecret();
        if (target instanceof RestaurantOwner o) return o.getTotpSecret();
        if (target instanceof DeliveryAgent a) return a.getTotpSecret();
        if (target instanceof Admin ad) return ad.getTotpSecret();
        return null;
    }

    public static void setTotpSecret(User user, String totpSecret) {
        User target = unproxy(user);
        if (target instanceof Customer c) c.setTotpSecret(totpSecret);
        else if (target instanceof RestaurantOwner o) o.setTotpSecret(totpSecret);
        else if (target instanceof DeliveryAgent a) a.setTotpSecret(totpSecret);
        else if (target instanceof Admin ad) ad.setTotpSecret(totpSecret);
    }

    private static User unproxy(User user) {
        return user == null ? null : (User) Hibernate.unproxy(user);
    }
}
