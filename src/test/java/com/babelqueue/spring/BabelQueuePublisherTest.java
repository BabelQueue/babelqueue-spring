package com.babelqueue.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.babelqueue.Envelope;
import com.babelqueue.PolyglotMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class BabelQueuePublisherTest {

    @Test
    void publishBuildsEnvelopeAndSendsToTheQueue() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        BabelQueuePublisher publisher = new BabelQueuePublisher(template, "default");

        String id = publisher.publish("urn:babel:orders:created", Map.of("order_id", 1042L), "orders");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("orders"), payload.capture());

        assertThat(payload.getValue()).isInstanceOf(Envelope.class);
        Envelope sent = (Envelope) payload.getValue();
        assertThat(sent.job()).isEqualTo("urn:babel:orders:created");
        assertThat(sent.data()).containsEntry("order_id", 1042L);
        assertThat(sent.meta().queue()).isEqualTo("orders");
        assertThat(sent.meta().lang()).isEqualTo("java");
        assertThat(id).isEqualTo(sent.meta().id());
    }

    @Test
    void publishFallsBackToTheDefaultQueue() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        BabelQueuePublisher publisher = new BabelQueuePublisher(template, "events");

        publisher.publish("urn:babel:orders:created", Map.of("x", 1L));

        verify(template).convertAndSend(eq("events"), org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void publishContinuesAnExistingTrace() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        BabelQueuePublisher publisher = new BabelQueuePublisher(template, "default");

        publisher.publish("urn:babel:orders:created", Map.of("x", 1L), "orders", "carry-over");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("orders"), payload.capture());
        assertThat(((Envelope) payload.getValue()).traceId()).isEqualTo("carry-over");
    }

    @Test
    void publishesAPolyglotMessageToDefaultAndExplicitQueues() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        BabelQueuePublisher publisher = new BabelQueuePublisher(template, "default");

        PolyglotMessage message = new PolyglotMessage() {
            @Override
            public String getBabelUrn() {
                return "urn:babel:orders:created";
            }

            @Override
            public Map<String, Object> toPayload() {
                return Map.of("order_id", 7L);
            }
        };

        publisher.publish(message); // default queue
        String id = publisher.publish(message, "orders"); // explicit queue

        verify(template).convertAndSend(eq("default"), org.mockito.ArgumentMatchers.<Object>any());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("orders"), payload.capture());
        Envelope sent = (Envelope) payload.getValue();
        assertThat(sent.job()).isEqualTo("urn:babel:orders:created");
        assertThat(sent.data()).containsEntry("order_id", 7L);
        assertThat(id).isEqualTo(sent.meta().id());
    }
}
