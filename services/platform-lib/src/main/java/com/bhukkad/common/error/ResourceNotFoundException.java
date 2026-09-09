package com.bhukkad.common.error;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}