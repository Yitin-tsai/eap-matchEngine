package com.eap.eap_matchengine.controller;

import com.eap.common.event.OrderCancelEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.dto.OrderBookResponseDto;
import com.eap.common.dto.MarketSummaryDto;
import com.eap.eap_matchengine.application.OrderCancelService;
import com.eap.eap_matchengine.application.OrderQueryService;
import com.eap.eap_matchengine.application.RedisMarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("v1/order")
public class OrderController {
    @Autowired
    OrderCancelService orderCancelService;

    @Autowired
    OrderQueryService orderQueryService;
    
    @Autowired
    RedisMarketDataService redisMarketDataService;

    @DeleteMapping("cancel")
    public boolean cancelOrder(@RequestBody OrderCancelEvent event) {
    return  orderCancelService.execute(event);
    }

    @GetMapping("query/{userId}")
    public ResponseEntity<List<OrderConfirmedEvent>> queryOrder(@PathVariable UUID userId) {
        List<OrderConfirmedEvent> orders = orderQueryService.excute(userId);
        return ResponseEntity.ok(orders);
    }
    
    /**
     * 獲取訂單簿數據
     * @param depth 深度（可選，默認10層）
     * @return 訂單簿數據
     */
    @GetMapping("orderbook")
    public ResponseEntity<OrderBookResponseDto> getOrderBook(
            @RequestParam(value = "depth", defaultValue = "10") int depth) {
        OrderBookResponseDto orderBook = redisMarketDataService.getOrderBookData(depth);
        return ResponseEntity.ok(orderBook);
    }
    
    /**
     * 獲取市場簡要統計
     * @return 最佳買賣價等基本信息
     */
    @GetMapping("market/summary")
    public ResponseEntity<MarketSummaryDto> getMarketSummary() {
        MarketSummaryDto summary = redisMarketDataService.getMarketSummary();
        return ResponseEntity.ok(summary);
    }
}
