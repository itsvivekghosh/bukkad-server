package com.bhukkad.util;

public class RequestUtils {

    public static String extractTokenFromRequestHeaders(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid or missing Authorization header. Use: Bearer <token>");
        }
        return authHeader.substring(7);
    }
}
