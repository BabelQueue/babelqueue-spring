package com.babelqueue.spring;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.PolyglotMessage;
import java.util.Map;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Ergonomic producer over a Spring {@link RabbitTemplate}. It builds the canonical
 * envelope with the core codec and publishes it through the template (whose
 * {@link BabelQueueMessageConverter} encodes it and stamps the contract AMQP
 * properties). Sends on the default exchange with the queue as the routing key.
 *
 * <pre>
 * babelQueue.publish("urn:babel:orders:created", Map.of("order_id", 1042L), "orders");
 * </pre>
 *
 * <p>{@link #publishWithHeaders} additionally carries out-of-band transport headers (e.g.
 * a W3C {@code traceparent}, ADR-0028) on the AMQP {@code MessageProperties} headers,
 * <b>beside</b> the contract {@code x-*} headers — the produce-side seam the optional core
 * {@code com.babelqueue.otel.HeaderSender} wires to.
 */
public class BabelQueuePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String defaultQueue;

    public BabelQueuePublisher(RabbitTemplate rabbitTemplate, String defaultQueue) {
        this.rabbitTemplate = rabbitTemplate;
        this.defaultQueue = defaultQueue == null || defaultQueue.isBlank() ? "default" : defaultQueue;
    }

    /** Publish a {@code (urn, data)} message to the default queue. Returns meta.id. */
    public String publish(String urn, Map<String, Object> data) {
        return publish(urn, data, defaultQueue, null);
    }

    /** Publish a {@code (urn, data)} message to {@code queue}. Returns meta.id. */
    public String publish(String urn, Map<String, Object> data, String queue) {
        return publish(urn, data, queue, null);
    }

    /**
     * Publish a {@code (urn, data)} message, optionally continuing an existing trace.
     * Returns the message id ({@code meta.id}).
     */
    public String publish(String urn, Map<String, Object> data, String queue, String traceId) {
        String target = queue == null || queue.isBlank() ? defaultQueue : queue;
        Envelope envelope = EnvelopeCodec.make(urn, data, target, traceId);
        rabbitTemplate.convertAndSend(target, (Object) envelope);
        return envelope.meta().id();
    }

    /**
     * Publish an already-built {@code envelope} together with out-of-band transport
     * {@code headers} (e.g. a W3C {@code traceparent}, ADR-0028). The headers are written
     * onto the AMQP {@code MessageProperties} headers <b>beside</b> the contract {@code x-*}
     * headers ({@link SpringHeaders#apply}: a contract header always wins a key collision),
     * never inside the frozen envelope (GR-1). This is the produce-side seam the optional
     * core {@code com.babelqueue.otel.HeaderSender} wires to; with no/empty headers it is
     * equivalent to a plain publish. Returns {@code meta.id}.
     */
    public String publishWithHeaders(Envelope envelope, Map<String, String> headers) {
        String target = envelope.meta() != null && envelope.meta().queue() != null
            && !envelope.meta().queue().isBlank() ? envelope.meta().queue() : defaultQueue;
        MessagePostProcessor addHeaders = message -> {
            SpringHeaders.apply(message.getMessageProperties(), headers);
            return message;
        };
        rabbitTemplate.convertAndSend(target, (Object) envelope, addHeaders);
        return envelope.meta().id();
    }

    /** Publish a {@link PolyglotMessage} to the default queue. Returns meta.id. */
    public String publish(PolyglotMessage message) {
        return publish(message, defaultQueue);
    }

    /** Publish a {@link PolyglotMessage} to {@code queue}. Returns meta.id. */
    public String publish(PolyglotMessage message, String queue) {
        String target = queue == null || queue.isBlank() ? defaultQueue : queue;
        Envelope envelope = EnvelopeCodec.fromMessage(message, target);
        rabbitTemplate.convertAndSend(target, (Object) envelope);
        return envelope.meta().id();
    }
}
