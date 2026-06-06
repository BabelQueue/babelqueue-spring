package com.babelqueue.spring;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.PolyglotMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;

/**
 * A Spring AMQP {@link MessageConverter} that speaks the canonical BabelQueue wire
 * envelope, so a Spring service interoperates with the PHP/Laravel, Python, Go,
 * Node and .NET SDKs over RabbitMQ.
 *
 * <p><b>Producing</b> ({@link #toMessage}): accepts an {@link Envelope} (already
 * built via the core codec) or a {@link PolyglotMessage}, encodes it to the
 * canonical JSON body and stamps the contract AMQP properties — {@code type} = URN,
 * {@code correlationId} = trace_id, {@code messageId} = meta.id, plus the
 * {@code x-schema-version} / {@code x-source-lang} / {@code x-attempts} headers.
 *
 * <p><b>Consuming</b> ({@link #fromMessage}): decodes the body and returns the
 * {@link Envelope}; a {@code @RabbitListener} method simply declares an
 * {@code Envelope} parameter. Non-conformant messages raise
 * {@link MessageConversionException} so Spring can reject / dead-letter them.
 *
 * <p>Register it as a single {@code MessageConverter} bean and Spring Boot wires it
 * into both the {@code RabbitTemplate} and the listener container factory.
 */
public class BabelQueueMessageConverter implements MessageConverter {

    private final String defaultQueue;

    public BabelQueueMessageConverter() {
        this("default");
    }

    public BabelQueueMessageConverter(String defaultQueue) {
        this.defaultQueue = defaultQueue == null || defaultQueue.isBlank() ? "default" : defaultQueue;
    }

    @Override
    public Message toMessage(Object object, MessageProperties messageProperties) throws MessageConversionException {
        Envelope envelope = toEnvelope(object);
        byte[] body = EnvelopeCodec.encode(envelope).getBytes(StandardCharsets.UTF_8);
        applyProperties(envelope, messageProperties);
        return new Message(body, messageProperties);
    }

    @Override
    public Object fromMessage(Message message) throws MessageConversionException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Envelope envelope = EnvelopeCodec.decode(body);
        if (!EnvelopeCodec.accepts(envelope)) {
            throw new MessageConversionException(
                "Rejected a non-conformant BabelQueue envelope (missing URN, unsupported "
                    + "meta.schema_version, blank trace_id, or missing data).");
        }
        return envelope;
    }

    private Envelope toEnvelope(Object object) {
        if (object instanceof Envelope envelope) {
            return envelope;
        }
        if (object instanceof PolyglotMessage message) {
            return EnvelopeCodec.fromMessage(message, defaultQueue);
        }
        throw new MessageConversionException(
            "BabelQueue can only convert an Envelope or a PolyglotMessage; got "
                + (object == null ? "null" : object.getClass().getName())
                + ". Build one with EnvelopeCodec.make(...) or use BabelQueuePublisher.");
    }

    private void applyProperties(Envelope envelope, MessageProperties props) {
        props.setContentType("application/json");
        props.setContentEncoding("utf-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setAppId("babelqueue");
        props.setType(envelope.job());
        props.setCorrelationId(envelope.traceId());
        props.setHeader("x-attempts", envelope.attempts());
        if (envelope.meta() != null) {
            props.setMessageId(envelope.meta().id());
            props.setHeader("x-schema-version", envelope.meta().schemaVersion());
            props.setHeader("x-source-lang", envelope.meta().lang());
        }
    }
}
