package com.eap.eap_matchengine;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Basic Spring Boot application context test.
 * Uses mocked beans to avoid requiring external dependencies (Redis, RabbitMQ) during testing.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:match_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.liquibase.enabled=false",
		"spring.rabbitmq.listener.simple.auto-startup=false",
		"eap.match-engine.order-admission-inbox.enabled=false",
		"eap.match-engine.trade-outbox-relay.enabled=false",
		"eap.match-engine.trade-checkpoint-relay.enabled=false",
		"eap.match-engine.reservation-reconciler.enabled=false",
		"eap.match-engine.reservation-cleanup.enabled=false"
})
class EapMatchengineApplicationTests {

	@MockitoBean
	private RedissonClient redissonClient;

	@MockitoBean
	private RedisTemplate<String, String> redisTemplate;

	@Test
	void contextLoads() {
		// Test that Spring Boot application context loads successfully
	}

}
