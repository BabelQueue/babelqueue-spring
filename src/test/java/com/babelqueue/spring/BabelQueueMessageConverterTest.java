package com.babelqueue.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.PolyglotMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;

class BabelQueueMessageConverterTest {

    private final BabelQueueMessageConverter converter = new BabelQueueMessageConverter("default");

    @Test
    void toMessageEncodesEnvelopeAndStampsContractProperties() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1042L), "orders", "trace-1");

        Message message = converter.toMessage(env, new MessageProperties());
        MessageProperties props = message.getMessageProperties();

        // body is the canonical envelope, decodable by the core
        Envelope decoded = EnvelopeCodec.decode(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(EnvelopeCodec.urn(decoded)).isEqualTo("urn:babel:orders:created");
        assertThat(decoded.data()).containsEntry("order_id", 1042L);

        // contract AMQP properties
        assertThat(props.getType()).isEqualTo("urn:babel:orders:created");
        assertThat(props.getCorrelationId()).isEqualTo("trace-1");
        assertThat(props.getMessageId()).isEqualTo(env.meta().id());
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getHeaders())
            .containsEntry("x-schema-version", 1)
            .containsEntry("x-source-lang", "java")
            .containsEntry("x-attempts", 0);
    }

    @Test
    void toMessageAcceptsPolyglotMessage() {
        Message message = converter.toMessage(new OrderCreated(7L), new MessageProperties());

        Envelope decoded = EnvelopeCodec.decode(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(decoded.job()).isEqualTo("urn:babel:orders:created");
        assertThat(decoded.data()).containsEntry("order_id", 7L);
        assertThat(decoded.meta().queue()).isEqualTo("default");
        assertThat(message.getMessageProperties().getType()).isEqualTo("urn:babel:orders:created");
    }

    @Test
    void fromMessageDecodesAValidEnvelope() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 99L), "orders", null);
        byte[] body = EnvelopeCodec.encode(env).getBytes(StandardCharsets.UTF_8);

        Object result = converter.fromMessage(new Message(body, new MessageProperties()));

        assertThat(result).isInstanceOf(Envelope.class);
        Envelope decoded = (Envelope) result;
        assertThat(EnvelopeCodec.urn(decoded)).isEqualTo("urn:babel:orders:created");
        assertThat(decoded.data()).containsEntry("order_id", 99L);
    }

    @Test
    void fromMessageRejectsNonConformantEnvelope() {
        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        assertThatThrownBy(() -> converter.fromMessage(message))
            .isInstanceOf(MessageConversionException.class);
    }

    @Test
    void toMessageRejectsUnsupportedType() {
        assertThatThrownBy(() -> converter.toMessage("just a string", new MessageProperties()))
            .isInstanceOf(MessageConversionException.class);
    }

    record OrderCreated(long orderId) implements PolyglotMessage {
        @Override
        public String getBabelUrn() {
            return "urn:babel:orders:created";
        }

        @Override
        public Map<String, Object> toPayload() {
            return Map.of("order_id", orderId);
        }
    }
}
