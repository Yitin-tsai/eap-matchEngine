package com.eap.eap_matchengine.configuration.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class RedisConnectionTuningConfig {

    @Bean
    public static BeanPostProcessor lettuceConnectionFactoryTuningPostProcessor(Environment environment) {
        boolean shareNativeConnection = environment.getProperty(
                "eap.redis.share-native-connection",
                Boolean.class,
                true);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof LettuceConnectionFactory factory) {
                    factory.setShareNativeConnection(shareNativeConnection);
                }
                return bean;
            }
        };
    }
}
