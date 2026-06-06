package com.babelqueue.spring;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the BabelQueue Spring adapter. Exposing a single
 * {@link MessageConverter} bean lets Spring Boot's RabbitMQ auto-configuration wire
 * it into both the {@code RabbitTemplate} (producing) and the default listener
 * container factory (consuming) — so {@code @RabbitListener} methods receive a
 * decoded {@link com.babelqueue.Envelope}. Both beans back off if the application
 * defines its own.
 */
@AutoConfiguration
@ConditionalOnClass({RabbitTemplate.class, MessageConverter.class})
@EnableConfigurationProperties(BabelQueueProperties.class)
public class BabelQueueAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public BabelQueueMessageConverter babelQueueMessageConverter(BabelQueueProperties properties) {
        return new BabelQueueMessageConverter(properties.getDefaultQueue());
    }

    @Bean
    @ConditionalOnBean(RabbitTemplate.class)
    @ConditionalOnMissingBean
    public BabelQueuePublisher babelQueuePublisher(RabbitTemplate rabbitTemplate, BabelQueueProperties properties) {
        return new BabelQueuePublisher(rabbitTemplate, properties.getDefaultQueue());
    }
}
