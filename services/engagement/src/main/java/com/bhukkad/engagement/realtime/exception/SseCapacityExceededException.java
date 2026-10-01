package com.bhukkad.engagement.realtime.exception;

public class SseCapacityExceededException extends RuntimeException {

    public SseCapacityExceededException(String message) {
        super(message);
    }
}
