package com.eap.eap_matchengine;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Basic Spring Boot application context test.
 * Uses mocked beans to avoid requiring external dependencies (Redis, RabbitMQ) during testing.
 */
@SpringBootTest
class EapMatchengineApplicationTests {

	@MockitoBean
	private RedissonClient redissonClient;

	@Test
	void contextLoads() {
		// Test that Spring Boot application context loads successfully
	}

}
