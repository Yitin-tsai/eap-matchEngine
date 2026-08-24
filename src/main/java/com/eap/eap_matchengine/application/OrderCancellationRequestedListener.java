package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancellationRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_CANCELLATION_REQUESTED_QUEUE;

@Component
@RequiredArgsConstructor
public class OrderCancellationRequestedListener {

    private final OrderCancellationCoordinator coordinator;

    @RabbitListener(
            queues = MATCH_ENGINE_ORDER_CANCELLATION_REQUESTED_QUEUE,
            concurrency = "${eap.match-engine.listeners.order-cancellation-requested.concurrency:4}")
    public void onRequested(OrderCancellationRequestedEvent request) {
        coordinator.request(request);
    }
}
