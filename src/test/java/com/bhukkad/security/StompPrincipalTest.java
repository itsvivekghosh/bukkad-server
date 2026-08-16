package com.bhukkad.security;

import com.bhukkad.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StompPrincipalTest {

    @Test
    void getName_returnsUserEmail() {
        User user = new User();
        user.setEmail("chef@bhukkad.com");

        StompPrincipal principal = new StompPrincipal(user);

        assertEquals("chef@bhukkad.com", principal.getName());
        assertEquals(user, principal.user());
    }
}
