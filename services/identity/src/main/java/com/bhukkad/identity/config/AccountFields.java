package com.bhukkad.identity.config;

import com.bhukkad.identity.domain.entity.Admin;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.entity.DeliveryAgent;
import com.bhukkad.identity.domain.entity.RestaurantOwner;
import com.bhukkad.identity.domain.entity.User;

public final class AccountFields {

    private AccountFields() {
    }

    public static String email(Customer customer) {
        return customer != null ? customer.getEmail() : null;
    }

    public static void setEmail(Customer customer, String email) {
        if (customer != null) customer.setEmail(email);
    }

    public static String password(Customer customer) {
        return customer != null ? customer.getPasswordHash() : null;
    }

    public static void setPassword(Customer customer, String password) {
        if (customer != null) customer.setPasswordHash(password);
    }

    public static String fullName(Customer customer) {
        return customer != null ? customer.getFullName() : null;
    }

    public static void setFullName(Customer customer, String fullName) {
        if (customer != null) customer.setFullName(fullName);
    }

    public static String phoneNumber(Customer customer) {
        return customer != null ? customer.getPhoneNumber() : null;
    }

    public static void setPhoneNumber(Customer customer, String phoneNumber) {
        if (customer != null) customer.setPhoneNumber(phoneNumber);
    }

    public static String profileImageUrl(Customer customer) {
        return null;
    }

    public static void setProfileImageUrl(Customer customer, String profileImageUrl) {
    }

    public static String totpSecret(Customer customer) {
        return null;
    }

    public static void setTotpSecret(Customer customer, String totpSecret) {
    }

    public static String email(User user) {
        if (user instanceof Admin a) return a.getEmail();
        if (user instanceof RestaurantOwner o) return o.getEmail();
        if (user instanceof DeliveryAgent d) return d.getEmail();
        return null;
    }

    public static void setEmail(User user, String email) {
        if (user instanceof Admin a) a.setEmail(email);
        else if (user instanceof RestaurantOwner o) o.setEmail(email);
        else if (user instanceof DeliveryAgent d) d.setEmail(email);
    }

    public static String password(User user) {
        return null;
    }

    public static void setPassword(User user, String password) {
    }

    public static String fullName(User user) {
        if (user instanceof Admin a) return a.getFullName();
        if (user instanceof RestaurantOwner o) return o.getFullName();
        if (user instanceof DeliveryAgent d) return d.getFullName();
        return null;
    }

    public static void setFullName(User user, String fullName) {
        if (user instanceof Admin a) a.setFullName(fullName);
        else if (user instanceof RestaurantOwner o) o.setFullName(fullName);
        else if (user instanceof DeliveryAgent d) d.setFullName(fullName);
    }

    public static String phoneNumber(User user) {
        if (user instanceof Admin a) return a.getPhoneNumber();
        if (user instanceof RestaurantOwner o) return o.getPhoneNumber();
        if (user instanceof DeliveryAgent d) return d.getPhoneNumber();
        return null;
    }

    public static void setPhoneNumber(User user, String phoneNumber) {
        if (user instanceof Admin a) a.setPhoneNumber(phoneNumber);
        else if (user instanceof RestaurantOwner o) o.setPhoneNumber(phoneNumber);
        else if (user instanceof DeliveryAgent d) d.setPhoneNumber(phoneNumber);
    }

    public static String profileImageUrl(User user) {
        return null;
    }

    public static void setProfileImageUrl(User user, String profileImageUrl) {
    }

    public static String totpSecret(User user) {
        return null;
    }

    public static void setTotpSecret(User user, String totpSecret) {
    }
}
