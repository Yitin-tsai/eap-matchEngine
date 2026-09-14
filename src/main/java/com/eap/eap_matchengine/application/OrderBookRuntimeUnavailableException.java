package com.eap.eap_matchengine.application;

public class OrderBookRuntimeUnavailableException extends RuntimeException {

    public OrderBookRuntimeUnavailableException(String message) {
        super(message);
    }

    public OrderBookRuntimeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
