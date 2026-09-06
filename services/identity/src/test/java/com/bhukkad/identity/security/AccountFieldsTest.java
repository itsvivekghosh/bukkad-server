package com.bhukkad.identity.security;

import com.bhukkad.identity.domain.Admin;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.DeliveryAgent;
import com.bhukkad.identity.domain.RestaurantOwner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountFieldsTest {

    @Test
    void customerAccessors_roundTrip() {
        Customer c = new Customer();
        c.setEmail("c@b.com");
        c.setPasswordHash("$2a$10$hashed");
        c.setFullName("Carol");
        c.setPhoneNumber("+919999999999");

        assertThat(AccountFields.email(c)).isEqualTo("c@b.com");
        assertThat(AccountFields.password(c)).isEqualTo("$2a$10$hashed");
        assertThat(AccountFields.fullName(c)).isEqualTo("Carol");
        assertThat(AccountFields.phoneNumber(c)).isEqualTo("+919999999999");

        AccountFields.setEmail(c, "c2@b.com");
        AccountFields.setPassword(c, "newhash");
        AccountFields.setFullName(c, "Carol 2");
        AccountFields.setPhoneNumber(c, "+910000000000");

        assertThat(c.getEmail()).isEqualTo("c2@b.com");
        assertThat(c.getPasswordHash()).isEqualTo("newhash");
        assertThat(c.getFullName()).isEqualTo("Carol 2");
        assertThat(c.getPhoneNumber()).isEqualTo("+910000000000");
    }

    @Test
    void userSubclassAccessors_work() {
        Admin admin = new Admin();
        admin.setEmail("a@b.com");
        admin.setFullName("Alice");
        admin.setPhoneNumber("+911111111111");

        assertThat(AccountFields.email(admin)).isEqualTo("a@b.com");
        assertThat(AccountFields.fullName(admin)).isEqualTo("Alice");
        assertThat(AccountFields.phoneNumber(admin)).isEqualTo("+911111111111");
        assertThat(AccountFields.password(admin)).isNull();
    }

    @Test
    void unsupportedUserType_returnsNull() {
        Customer c = new Customer();
        assertThat(AccountFields.profileImageUrl(c)).isNull();
        assertThat(AccountFields.totpSecret(c)).isNull();
    }
}
