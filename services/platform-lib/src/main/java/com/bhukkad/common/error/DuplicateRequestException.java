package com.bhukkad.common.error;

public class DuplicateRequestException extends BusinessException {
    public DuplicateRequestException(String message) {
        super("DUPLICATE_REQUEST", message);
    }
}