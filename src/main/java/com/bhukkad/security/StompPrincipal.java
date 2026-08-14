package com.bhukkad.security;

import com.bhukkad.entity.User;

import java.security.Principal;

public record StompPrincipal(User user) implements Principal {

    @Override
    public String getName() {
        return user.getEmail();
    }
}
