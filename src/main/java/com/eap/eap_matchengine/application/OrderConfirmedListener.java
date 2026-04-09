package com.eap.eap_matchengine.application;


import com.eap.common.event.OrderConfirmedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_CONFIRMED_QUEUE;

@Component
@RequiredArgsConstructor
public class OrderConfirmedListener {

  private final MatchingEngineService matchingEngineService;

  @RabbitListener(queues = MATCH_ENGINE_ORDER_CONFIRMED_QUEUE)
  public void handleConfirmedOrder(OrderConfirmedEvent event) throws JsonProcessingException {
    System.out.println("Confirmed order received: " + event);

    matchingEngineService.tryMatch(event);
  }
}
