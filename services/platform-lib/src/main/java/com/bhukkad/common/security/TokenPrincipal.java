package com.bhukkad.common.security;

public record TokenPrincipal(Long userId, String email, String scope) {
}