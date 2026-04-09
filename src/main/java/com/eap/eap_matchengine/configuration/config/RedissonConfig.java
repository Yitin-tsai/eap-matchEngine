package com.eap.eap_matchengine.configuration.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson configuration for distributed locks and synchronization.
 * Provides RedissonClient bean for distributed lock operations in the matching engine.
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Creates and configures RedissonClient bean for distributed operations.
     *
     * @return Configured RedissonClient instance
     */
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        // Configure single server mode
        String redisAddress = "redis://" + redisHost + ":" + redisPort;

        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.useSingleServer()
                    .setAddress(redisAddress)
                    .setPassword(redisPassword)
                    .setConnectionPoolSize(64)
                    .setConnectionMinimumIdleSize(10)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);
        } else {
            config.useSingleServer()
                    .setAddress(redisAddress)
                    .setConnectionPoolSize(64)
                    .setConnectionMinimumIdleSize(10)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);
        }

        return Redisson.create(config);
    }
}
