package com.bhukkad.security;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StompPrincipalTest {

    @Test
    void getName_returnsUserEmail() {
        Customer user = new Customer();
        com.bhukkad.security.AccountFields.setEmail(user, "chef@bhukkad.com");

        StompPrincipal principal = new StompPrincipal(user);

        assertEquals("chef@bhukkad.com", principal.getName());
        assertEquals(user, principal.user());
    }
}
