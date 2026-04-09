package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.dto.OrderBookResponseDto;
import com.eap.common.dto.MarketSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis 市場數據查詢服務
 * 專門用於從 Redis 訂單簿中提取市場數據，供 WebSocket 推送使用
 * 與 RedisOrderBookService 分離，保持各自職責單一
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisMarketDataService {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String BUY_ORDERBOOK_KEY = "orderbook:buy";
    private static final String SELL_ORDERBOOK_KEY = "orderbook:sell";
    
    /**
     * 獲取訂單簿數據（買盤和賣盤的聚合數據）
     * @param depth 深度（返回多少個價格層級，默認10層）
     * @return 訂單簿響應DTO
     */
    public OrderBookResponseDto getOrderBookData(int depth) {
        try {
            List<OrderBookResponseDto.OrderBookLevel> bids = getBuyOrderBookLevels(depth);
            List<OrderBookResponseDto.OrderBookLevel> asks = getSellOrderBookLevels(depth);
            
            return OrderBookResponseDto.builder()
                    .bids(bids)
                    .asks(asks)
                    .build();
                    
        } catch (Exception e) {
            log.error("獲取訂單簿數據失敗: {}", e.getMessage());
            return OrderBookResponseDto.builder()
                    .bids(List.of())
                    .asks(List.of())
                    .build();
        }
    }
    
    /**
     * 獲取買盤數據（價格從高到低排序）
     */
    private List<OrderBookResponseDto.OrderBookLevel> getBuyOrderBookLevels(int depth) {
        try {
            // 獲取買盤訂單ID（價格從高到低）
            Set<String> buyOrderIds = redisTemplate.opsForZSet().reverseRange(BUY_ORDERBOOK_KEY, 0, -1);
            Map<Integer, PriceLevelData> buyLevels = new LinkedHashMap<>();
            
            if (buyOrderIds != null) {
                for (String orderId : buyOrderIds) {
                    String orderJson = redisTemplate.opsForValue().get("order:" + orderId);
                    if (orderJson != null) {
                        OrderConfirmedEvent order = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
                        buyLevels.computeIfAbsent(order.getPrice(), price -> 
                            new PriceLevelData(price))
                            .addOrder(order.getAmount());
                    }
                }
            }
            
            // 轉換為響應DTO並限制深度
            return buyLevels.values().stream()
                    .limit(depth)
                    .map(level -> OrderBookResponseDto.OrderBookLevel.builder()
                            .price(level.getPrice())
                            .amount(level.getQuantity())
                            .orderCount(level.getOrderCount())
                            .build())
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("獲取買盤數據失敗: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * 獲取賣盤數據（價格從低到高排序）
     */
    private List<OrderBookResponseDto.OrderBookLevel> getSellOrderBookLevels(int depth) {
        try {
            // 獲取賣盤訂單ID（價格從低到高）
            Set<String> sellOrderIds = redisTemplate.opsForZSet().range(SELL_ORDERBOOK_KEY, 0, -1);
            Map<Integer, PriceLevelData> sellLevels = new LinkedHashMap<>();
            
            if (sellOrderIds != null) {
                for (String orderId : sellOrderIds) {
                    String orderJson = redisTemplate.opsForValue().get("order:" + orderId);
                    if (orderJson != null) {
                        OrderConfirmedEvent order = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
                        sellLevels.computeIfAbsent(order.getPrice(), price -> 
                            new PriceLevelData(price))
                            .addOrder(order.getAmount());
                    }
                }
            }
            
            // 轉換為響應DTO並限制深度
            return sellLevels.values().stream()
                    .limit(depth)
                    .map(level -> OrderBookResponseDto.OrderBookLevel.builder()
                            .price(level.getPrice())
                            .amount(level.getQuantity())
                            .orderCount(level.getOrderCount())
                            .build())
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("獲取賣盤數據失敗: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * 獲取市場簡要統計
     * @return 包含最佳買價、最佳賣價等基本信息
     */
    public MarketSummaryDto getMarketSummary() {
        try {
            // 獲取最佳買價（最高買價）
            Set<String> topBuyOrder = redisTemplate.opsForZSet().reverseRange(BUY_ORDERBOOK_KEY, 0, 0);
            Integer bestBidPrice = null;
            if (topBuyOrder != null && !topBuyOrder.isEmpty()) {
                Double price = redisTemplate.opsForZSet().score(BUY_ORDERBOOK_KEY, topBuyOrder.iterator().next());
                bestBidPrice = price != null ? price.intValue() : null;
            }
            
            // 獲取最佳賣價（最低賣價）
            Set<String> topSellOrder = redisTemplate.opsForZSet().range(SELL_ORDERBOOK_KEY, 0, 0);
            Integer bestAskPrice = null;
            if (topSellOrder != null && !topSellOrder.isEmpty()) {
                Double price = redisTemplate.opsForZSet().score(SELL_ORDERBOOK_KEY, topSellOrder.iterator().next());
                bestAskPrice = price != null ? price.intValue() : null;
            }
            
            MarketSummaryDto summary = new MarketSummaryDto();
            summary.setBestBidPrice(bestBidPrice);
            summary.setBestAskPrice(bestAskPrice);
            return summary;
            
        } catch (Exception e) {
            log.error("獲取市場簡要統計失敗: {}", e.getMessage());
            MarketSummaryDto summary = new MarketSummaryDto();
            summary.setBestBidPrice(null);
            summary.setBestAskPrice(null);
            return summary;
        }
    }
    
    /**
     * 內部類：價格層級數據聚合
     */
    private static class PriceLevelData {
        private final Integer price;
        private Integer quantity = 0;
        private Integer orderCount = 0;
        
        public PriceLevelData(Integer price) {
            this.price = price;
        }
        
        public void addOrder(Integer amount) {
            this.quantity += amount;
            this.orderCount++;
        }
        
        public Integer getPrice() { return price; }
        public Integer getQuantity() { return quantity; }
        public Integer getOrderCount() { return orderCount; }
    }
}
