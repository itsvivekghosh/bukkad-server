package com.bhukkad.security;

import com.bhukkad.entity.Admin;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the typed credential/PII accessors over {@code User}-typed
 * references (V62 segregation): every role type must resolve through the
 * helper, and null inputs must be handled without throwing.
 */
class AccountFieldsTest {

    @Test
    void email_resolvesForEveryRoleType() {
        Customer customer = new Customer();
        AccountFields.setEmail(customer, "c@test.com");
        RestaurantOwner owner = new RestaurantOwner();
        AccountFields.setEmail(owner, "o@test.com");
        DeliveryAgent agent = new DeliveryAgent();
        AccountFields.setEmail(agent, "a@test.com");
        Admin admin = new Admin();
        AccountFields.setEmail(admin, "ad@test.com");

        assertThat(AccountFields.email(customer)).isEqualTo("c@test.com");
        assertThat(AccountFields.email(owner)).isEqualTo("o@test.com");
        assertThat(AccountFields.email(agent)).isEqualTo("a@test.com");
        assertThat(AccountFields.email(admin)).isEqualTo("ad@test.com");
    }

    @Test
    void password_roundTripsOnAllRoleTypes() {
        Customer customer = new Customer();
        RestaurantOwner owner = new RestaurantOwner();
        DeliveryAgent agent = new DeliveryAgent();
        Admin admin = new Admin();

        AccountFields.setPassword(customer, "pw1");
        AccountFields.setPassword(owner, "pw2");
        AccountFields.setPassword(agent, "pw3");
        AccountFields.setPassword(admin, "pw4");

        assertThat(AccountFields.password(customer)).isEqualTo("pw1");
        assertThat(AccountFields.password(owner)).isEqualTo("pw2");
        assertThat(AccountFields.password(agent)).isEqualTo("pw3");
        assertThat(AccountFields.password(admin)).isEqualTo("pw4");
    }

    @Test
    void fullName_phoneNumber_profileImage_roundTrip() {
        Customer customer = new Customer();
        AccountFields.setFullName(customer, "Ada Lovelace");
        AccountFields.setPhoneNumber(customer, "9000000001");
        AccountFields.setProfileImageUrl(customer, "https://img.test/ada.png");

        assertThat(AccountFields.fullName(customer)).isEqualTo("Ada Lovelace");
        assertThat(AccountFields.phoneNumber(customer)).isEqualTo("9000000001");
        assertThat(AccountFields.profileImageUrl(customer)).isEqualTo("https://img.test/ada.png");
    }

    @Test
    void totpSecret_roundTrips() {
        Admin admin = new Admin();
        AccountFields.setTotpSecret(admin, "JBSWY3DPEHPK3PXP");
        assertThat(AccountFields.totpSecret(admin)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void accessorsOnNullUser_returnNullWithoutThrowing() {
        assertThat(AccountFields.email(null)).isNull();
        assertThat(AccountFields.password(null)).isNull();
        assertThat(AccountFields.fullName(null)).isNull();
        assertThat(AccountFields.phoneNumber(null)).isNull();
        assertThat(AccountFields.profileImageUrl(null)).isNull();
        assertThat(AccountFields.totpSecret(null)).isNull();
    }

    @Test
    void settersOnNullUser_areNoOps() {
        AccountFields.setEmail(null, "x");
        AccountFields.setPassword(null, "x");
        AccountFields.setFullName(null, "x");
        AccountFields.setPhoneNumber(null, "x");
        AccountFields.setProfileImageUrl(null, "x");
        AccountFields.setTotpSecret(null, "x");
    }

    @Test
    void registryFieldsStayOnUserBase() {
        // Role and active are registry-scoped: they must be reachable directly
        // on the base type without AccountFields.
        Customer customer = new Customer();
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer.setActive(false);

        assertThat(customer.getRole()).isEqualTo(Customer.UserRole.CUSTOMER);
        assertThat(customer.getActive()).isFalse();
    }
}
