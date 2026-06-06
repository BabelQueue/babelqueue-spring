package com.babelqueue.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BabelQueueAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BabelQueueAutoConfiguration.class));

    @Test
    void registersTheMessageConverter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BabelQueueMessageConverter.class);
            // No RabbitTemplate in the context → no publisher.
            assertThat(context).doesNotHaveBean(BabelQueuePublisher.class);
        });
    }

    @Test
    void registersThePublisherWhenARabbitTemplateIsPresent() {
        runner.withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .run(context -> {
                assertThat(context).hasSingleBean(BabelQueueMessageConverter.class);
                assertThat(context).hasSingleBean(BabelQueuePublisher.class);
            });
    }

    @Test
    void backsOffWhenTheApplicationProvidesItsOwnConverter() {
        runner.withBean("customConverter", MessageConverter.class, SimpleMessageConverter::new)
            .run(context -> {
                assertThat(context).doesNotHaveBean(BabelQueueMessageConverter.class);
                assertThat(context).hasSingleBean(MessageConverter.class);
            });
    }

    @Test
    void honorsCustomDefaultQueueProperty() {
        runner.withPropertyValues("babelqueue.default-queue=events")
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .run(context -> assertThat(context.getBean(BabelQueueProperties.class).getDefaultQueue())
                .isEqualTo("events"));
    }
}
