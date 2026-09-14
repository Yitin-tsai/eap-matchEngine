package com.eap.eap_matchengine.controller;

import com.eap.eap_matchengine.application.OrderBookRuntimeUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class OrderBookRuntimeExceptionHandler {

    @ExceptionHandler(OrderBookRuntimeUnavailableException.class)
    public ResponseEntity<Map<String, String>> orderBookUnavailable(
            OrderBookRuntimeUnavailableException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "code", "ORDER_BOOK_NOT_READY",
                        "message", failure.getMessage()));
    }
}
