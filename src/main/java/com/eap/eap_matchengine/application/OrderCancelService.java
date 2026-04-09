package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancelEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderCancelService {

    @Autowired
    RedisOrderBookService redisService;


    public boolean execute(OrderCancelEvent cancelEvent) {

        return redisService.cancelOrder(cancelEvent);

    }
}
