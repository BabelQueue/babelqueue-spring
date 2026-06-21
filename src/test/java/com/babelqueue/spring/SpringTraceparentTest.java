package com.babelqueue.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * The Spring AMQP wiring of the out-of-band {@code traceparent} header (ADR-0028): produce
 * writes it onto {@code MessageProperties} headers beside the contract {@code x-*} headers
 * (contract wins), consume surfaces them as a {@code Map<String,String>}, and a
 * publish→consume round-trip (converter + post-processor) carries the header end to end so
 * the consumer span is a true child of the producer span. No broker (the
 * {@code RabbitTemplate} is mocked; the converter runs in-process).
 */
class SpringTraceparentTest {

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private final BabelQueueMessageConverter converter = new BabelQueueMessageConverter("default");

    @Test
    void applyWritesHeadersBesideContractHeadersAndContractWins() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("x", 1L), "orders", "trace-1");
        Message message = converter.toMessage(env, new MessageProperties()); // stamps x-*
        MessageProperties props = message.getMessageProperties();

        SpringHeaders.apply(props, Map.of(
            "traceparent", TRACEPARENT,
            "x-schema-version", "999")); // collides with a contract header -> must not win

        assertThat((Object) props.getHeader("traceparent")).isEqualTo(TRACEPARENT);
        assertThat((Object) props.getHeader("x-schema-version")).isEqualTo(1); // contract value preserved
    }

    @Test
    void applyIsANoOpForBlankAndEmptyInputs() {
        MessageProperties props = new MessageProperties();
        SpringHeaders.apply(props, Map.of());
        SpringHeaders.apply(props, null);
        SpringHeaders.apply(null, Map.of("traceparent", TRACEPARENT));
        SpringHeaders.apply(props, Map.of("", "x")); // blank key skipped
        assertThat(props.getHeaders()).isEmpty();
    }

    @Test
    void ofSurfacesDeliveredHeadersAsAMap() {
        MessageProperties props = new MessageProperties();
        props.setHeader("traceparent", TRACEPARENT);
        props.setHeader("x-schema-version", 1);
        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), props);

        Map<String, String> headers = SpringHeaders.of(message);
        assertThat(headers).containsEntry("traceparent", TRACEPARENT).containsEntry("x-schema-version", "1");
        assertThat(SpringHeaders.of((Message) null)).isEmpty();
        assertThat(SpringHeaders.of((MessageProperties) null)).isEmpty();
    }

    @Test
    void publishWithHeadersSendsThroughAPostProcessorThatStampsTheHeader() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        BabelQueuePublisher publisher = new BabelQueuePublisher(template, "default");
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("x", 1L), "orders", "trace-1");

        String id = publisher.publishWithHeaders(env, Map.of("traceparent", TRACEPARENT));
        assertThat(id).isEqualTo(env.meta().id());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq("orders"), payload.capture(), post.capture());

        // Drive the converter + the captured post-processor to get the final wire message.
        Message converted = converter.toMessage(payload.getValue(), new MessageProperties());
        Message finalMsg = post.getValue().postProcessMessage(converted);
        assertThat((Object) finalMsg.getMessageProperties().getHeader("traceparent")).isEqualTo(TRACEPARENT);
        // GR-1: traceparent is not in the body; GR-4: trace_id preserved.
        String body = new String(finalMsg.getBody(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("traceparent");
        assertThat(EnvelopeCodec.decode(body).traceId()).isEqualTo("trace-1");
    }

    @Test
    void endToEndTraceparentMakesConsumerSpanAChildOfTheProducerSpan() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Tracer tracer = sdk.getTracer("test");

        RabbitTemplate template = mock(RabbitTemplate.class);
        BabelQueuePublisher publisher = new BabelQueuePublisher(template, "default");

        // Producer: a PRODUCER span injects traceparent onto the headers via the otel
        // HeaderSender seam -> BabelQueuePublisher.publishWithHeaders.
        com.babelqueue.otel.Tracing.publish(
            tracer, "urn:babel:orders:created", Map.of("order_id", 7), "orders",
            (envelope, headers) -> publisher.publishWithHeaders(envelope, headers));

        // Reconstruct the delivered AMQP message (converter + post-processor).
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq("orders"), payload.capture(), post.capture());
        Message delivered = post.getValue().postProcessMessage(
            converter.toMessage(payload.getValue(), new MessageProperties()));
        Envelope env = (Envelope) converter.fromMessage(delivered);

        // Consumer: wrapHandler reads the delivered headers and parents the CONSUMER span on
        // the carried traceparent.
        com.babelqueue.otel.Tracing.wrapHandler(tracer, e -> { }, () -> SpringHeaders.of(delivered))
            .handle(env);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData producer = spanByName(spans, "publish urn:babel:orders:created");
        SpanData consumer = spanByName(spans, "process urn:babel:orders:created");
        assertThat(consumer.getParentSpanContext().getSpanId()).isEqualTo(producer.getSpanContext().getSpanId());
        assertThat(consumer.getTraceId()).isEqualTo(producer.getSpanContext().getTraceId());
        assertThat(consumer.getParentSpanContext().isRemote()).isTrue();
        provider.close();
    }

    private static SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
