package com.eap.eap_matchengine.application;


import com.eap.common.event.OrderConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class OrderQueryService {
    @Autowired
    RedisOrderBookService redisOrderBookService;

    public List<OrderConfirmedEvent> excute(UUID userId) {
        log.info("Querying orders for user: {}", userId);
        return redisOrderBookService.getOrderByUserId(userId);


    }
}
