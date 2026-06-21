package com.babelqueue.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * The out-of-band transport-header seam for the Spring AMQP adapter (ADR-0028).
 *
 * <p>Out-of-band headers (e.g. a W3C {@code traceparent}) ride on the AMQP
 * {@code MessageProperties} headers <b>beside</b> the contract {@code x-*} headers
 * ({@code x-schema-version}/{@code x-source-lang}/{@code x-attempts}) that
 * {@link BabelQueueMessageConverter} stamps — never inside the frozen envelope (GR-1).
 *
 * <ul>
 *   <li><b>Produce</b> ({@link #apply}): {@link BabelQueuePublisher#publishWithHeaders}
 *       merges the headers onto the message's properties; a contract header already present
 *       always wins a key collision, and blank keys/values are skipped.</li>
 *   <li><b>Consume</b> ({@link #of(Message)} / {@link #of(MessageProperties)}): surfaces a
 *       delivered message's headers as a flat {@code Map<String, String>}, the seam a
 *       {@code @RabbitListener} wires to the optional core
 *       {@code com.babelqueue.otel.Tracing#wrapHandler(io.opentelemetry.api.trace.Tracer,
 *       com.babelqueue.idempotency.Handler, java.util.function.Supplier)} so a carried
 *       {@code traceparent} makes the consumer span a true child of the producer span.</li>
 * </ul>
 *
 * <pre>{@code
 * @RabbitListener(queues = "orders")
 * void onOrder(Envelope env, Message message) throws Exception {
 *     Tracing.wrapHandler(tracer, h, () -> SpringHeaders.of(message)).handle(env);
 * }
 * }</pre>
 *
 * <p>Reading or writing the headers requires no OpenTelemetry dependency; the map is a
 * plain {@code Map<String, String>}.
 */
public final class SpringHeaders {

    private SpringHeaders() {}

    /**
     * Writes the out-of-band {@code headers} onto {@code props} beside the contract
     * {@code x-*} headers. A blank key or value is skipped, and a header is never written
     * over a property already set (the contract projection wins). A {@code null}/empty map
     * is a no-op.
     */
    static void apply(MessageProperties props, Map<String, String> headers) {
        if (props == null || headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
                continue;
            }
            if (props.getHeader(key) != null) {
                continue; // contract / pre-existing header wins
            }
            props.setHeader(key, value);
        }
    }

    /**
     * Returns the delivered message's {@code MessageProperties} headers as a
     * {@code Map<String, String>}. An empty map for a {@code null} message.
     */
    public static Map<String, String> of(Message message) {
        return message == null ? Map.of() : of(message.getMessageProperties());
    }

    /**
     * Returns {@code props}' headers as a flat {@code Map<String, String>}
     * (each value rendered via {@code toString}; blank values dropped). An empty map for
     * {@code null} props or no headers.
     */
    public static Map<String, String> of(MessageProperties props) {
        Map<String, String> out = new LinkedHashMap<>();
        if (props == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : props.getHeaders().entrySet()) {
            Object value = e.getValue();
            if (value != null) {
                String str = value.toString();
                if (!str.isEmpty()) {
                    out.put(e.getKey(), str);
                }
            }
        }
        return out;
    }
}
