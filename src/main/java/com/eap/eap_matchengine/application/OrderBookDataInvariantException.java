package com.eap.eap_matchengine.application;

/**
 * Signals durable or Redis order-book data that cannot become valid through retry.
 */
public class OrderBookDataInvariantException extends IllegalStateException {

    public OrderBookDataInvariantException(String message) {
        super(message);
    }

    public OrderBookDataInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
