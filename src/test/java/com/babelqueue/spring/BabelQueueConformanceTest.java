package com.babelqueue.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.PolyglotMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minidev.json.JSONValue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;

/**
 * Runs the shared cross-SDK conformance suite (vendored under
 * {@code src/test/resources/conformance}) through the Spring AMQP adapter path —
 * {@link BabelQueueMessageConverter#fromMessage} / {@link BabelQueueMessageConverter#toMessage}
 * — the same fixtures every BabelQueue SDK must satisfy. Per-message fields
 * (meta.id, trace_id, meta.created_at) are intrinsically unique and are checked
 * for presence only.
 */
class BabelQueueConformanceTest {

    private static final Map<String, Object> MANIFEST = asMap(JSONValue.parse(readResource("/conformance/manifest.json")));

    private final BabelQueueMessageConverter converter = new BabelQueueMessageConverter("default");

    @Test
    void manifestMatchesCoreSchemaVersion() {
        assertThat(((Number) MANIFEST.get("schema_version")).intValue()).isEqualTo(EnvelopeCodec.SCHEMA_VERSION);
        assertThat((List<?>) MANIFEST.get("cases")).isNotEmpty();
    }

    @TestFactory
    Stream<DynamicTest> conformanceCasesThroughTheConverter() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object raw : (List<?>) MANIFEST.get("cases")) {
            Map<String, Object> c = asMap(raw);
            tests.add(dynamicTest((String) c.get("name"), () -> runCase(c)));
        }
        return tests.stream();
    }

    /**
     * {@code rabbitmq.property_projection}: the manifest's envelope is pushed through
     * {@code toMessage}; every listed property and header must match exactly, and nothing
     * outside the contract's header set may be stamped.
     */
    @Test
    void rabbitmqPropertyProjectionMatchesManifest() {
        Map<String, Object> projection = asMap(asMap(MANIFEST.get("rabbitmq")).get("property_projection"));
        Envelope env = EnvelopeCodec.decode(readResource("/conformance/" + projection.get("envelope_file")));
        assertThat(EnvelopeCodec.accepts(env)).isTrue();

        Message out = converter.toMessage(env, new MessageProperties());
        MessageProperties props = out.getMessageProperties();
        Map<String, Object> expectedProps = asMap(projection.get("properties"));
        Map<String, Object> expectedHeaders = asMap(projection.get("headers"));

        assertThat(props.getType()).isEqualTo(expectedProps.get("type"));
        assertThat(props.getCorrelationId()).isEqualTo(expectedProps.get("correlation_id"));
        assertThat(props.getMessageId()).isEqualTo(expectedProps.get("message_id"));
        assertThat(props.getAppId()).isEqualTo(expectedProps.get("app_id"));
        assertThat(props.getContentType()).isEqualTo(expectedProps.get("content_type"));
        assertThat(expectedProps.keySet())
            .containsExactlyInAnyOrder("type", "correlation_id", "message_id", "app_id", "content_type");

        // Header values are native-typed: numbers stay integers, strings stay strings.
        assertThat(props.getHeaders()).hasSameSizeAs(expectedHeaders);
        expectedHeaders.forEach((key, value) -> assertThat(props.getHeaders().get(key))
            .as("header %s", key)
            .isEqualTo(value instanceof Number n ? (Object) n.intValue() : value));
    }

    /**
     * {@code roundtrip}: fromMessage -> attempts+1 -> toMessage — unknown keys must survive the
     * adapter path unchanged (forward-compatible re-emit on retry/redrive).
     */
    @TestFactory
    Stream<DynamicTest> roundtripThroughTheConverter() {
        return sectionCases("roundtrip").stream().map(c -> dynamicTest((String) c.get("name"), () -> {
            Message inbound = new Message(fixtureBytes(c), new MessageProperties());
            Envelope decoded = (Envelope) converter.fromMessage(inbound);
            Message outbound = converter.toMessage(decoded.withAttempts(decoded.attempts() + 1), new MessageProperties());
            Object out = JSONValue.parse(new String(outbound.getBody(), StandardCharsets.UTF_8));

            assertThat(normalize(resolve(out, "/attempts"))).isEqualTo(normalize(c.get("expect_attempts")));
            Map<String, Object> preserved = asMap(c.get("expect_preserved"));
            assertThat(preserved).isNotEmpty();
            preserved.forEach((pointer, expected) -> {
                Object actual = resolve(out, pointer);
                assertThat(actual).as("pointer %s missing after re-emit", pointer).isNotSameAs(ABSENT);
                assertThat(normalize(actual)).as("pointer %s", pointer).isEqualTo(normalize(expected));
            });
        }));
    }

    /**
     * {@code forbidden_keys}: the adapter must accept the message (K-15 warns, never rejects)
     * and must never re-emit the forbidden key. The warning hook itself belongs to the core
     * codec and is asserted by the core suite; the converter exposes no hook.
     */
    @TestFactory
    Stream<DynamicTest> forbiddenKeysAreNeverReEmitted() {
        return sectionCases("forbidden_keys").stream().map(c -> dynamicTest((String) c.get("name"), () -> {
            assertThat(c.get("expect")).isEqualTo("warn");
            Message inbound = new Message(fixtureBytes(c), new MessageProperties());
            Envelope decoded = (Envelope) converter.fromMessage(inbound);
            Message outbound = converter.toMessage(decoded, new MessageProperties());
            Object out = JSONValue.parse(new String(outbound.getBody(), StandardCharsets.UTF_8));

            for (Object pointer : (List<?>) c.get("expect_absent_after_reencode")) {
                assertThat(resolve(out, (String) pointer)).as("%s must not be re-emitted", pointer).isSameAs(ABSENT);
            }
        }));
    }

    /**
     * {@code data_shape}: {@code encode} cases build an envelope and emit it via
     * {@code toMessage} ({@code {}} must stay an object, never {@code []}); {@code decode}
     * cases must be rejected by {@code fromMessage} when {@code valid} is false.
     */
    @TestFactory
    Stream<DynamicTest> dataShapeThroughTheConverter() {
        return sectionCases("data_shape").stream().map(c -> dynamicTest((String) c.get("name"), () -> {
            String mode = (String) c.get("mode");
            if ("encode".equals(mode)) {
                BabelQueueMessageConverter queueConverter = new BabelQueueMessageConverter((String) c.get("queue"));
                PolyglotMessage message = new PolyglotMessage() {
                    @Override
                    public String getBabelUrn() {
                        return (String) c.get("urn");
                    }

                    @Override
                    public Map<String, Object> toPayload() {
                        return asMap(c.get("data"));
                    }
                };
                Message outbound = queueConverter.toMessage(message, new MessageProperties());
                Object out = JSONValue.parse(new String(outbound.getBody(), StandardCharsets.UTF_8));
                Object expected = JSONValue.parse((String) c.get("expect_encoded_data_json"));
                assertThat(normalize(resolve(out, "/data"))).isEqualTo(normalize(expected));
            } else {
                assertThat(mode).isEqualTo("decode");
                Message inbound = new Message(fixtureBytes(c), new MessageProperties());
                if (Boolean.TRUE.equals(c.get("valid"))) {
                    assertThat(converter.fromMessage(inbound)).isInstanceOf(Envelope.class);
                } else {
                    assertThatThrownBy(() -> converter.fromMessage(inbound))
                        .as("data_shape decode must be rejected (%s)", c.getOrDefault("reason", ""))
                        .isInstanceOf(MessageConversionException.class);
                }
            }
        }));
    }

    private static List<Map<String, Object>> sectionCases(String section) {
        Object raw = asMap(MANIFEST.get(section)).get("cases");
        assertThat(raw).as("manifest section '%s' must have cases", section).isInstanceOf(List.class);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            out.add(asMap(o));
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    private static byte[] fixtureBytes(Map<String, Object> c) {
        return readResource("/conformance/" + c.get("file")).getBytes(StandardCharsets.UTF_8);
    }

    /** Sentinel for "no value at this pointer" (distinct from a JSON null). */
    private static final Object ABSENT = new Object();

    /** RFC 6901 JSON pointer resolution; returns {@link #ABSENT} when the path does not exist. */
    private static Object resolve(Object doc, String pointer) {
        if (pointer.isEmpty()) {
            return doc;
        }
        Object current = doc;
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return ABSENT;
                }
                current = map.get(token);
            } else if (current instanceof List<?> list && token.matches("0|[1-9][0-9]*")
                && Integer.parseInt(token) < list.size()) {
                current = list.get(Integer.parseInt(token));
            } else {
                return ABSENT;
            }
        }
        return current;
    }

    private void runCase(Map<String, Object> c) {
        byte[] body = readResource("/conformance/" + c.get("file")).getBytes(StandardCharsets.UTF_8);
        Message inbound = new Message(body, new MessageProperties());

        if (!Boolean.TRUE.equals(c.get("valid"))) {
            assertThatThrownBy(() -> converter.fromMessage(inbound))
                .as("invalid fixture must be rejected (%s)", c.getOrDefault("reason", ""))
                .isInstanceOf(MessageConversionException.class);
            return;
        }

        // Consume path: fromMessage must accept and derive the manifest's expectations.
        Object result = converter.fromMessage(inbound);
        assertThat(result).isInstanceOf(Envelope.class);
        Envelope env = (Envelope) result;
        Map<String, Object> expect = asMap(c.get("expect"));
        assertExpectations(env, expect);

        // Produce path: toMessage must re-emit a body the core decodes to the same
        // expectations, with the contract AMQP properties stamped.
        Message outbound = converter.toMessage(env, new MessageProperties());
        Envelope reDecoded = EnvelopeCodec.decode(new String(outbound.getBody(), StandardCharsets.UTF_8));
        assertThat(EnvelopeCodec.accepts(reDecoded)).isTrue();
        assertExpectations(reDecoded, expect);
        assertThat(reDecoded.traceId()).isEqualTo(env.traceId());
        assertThat(reDecoded.meta().id()).isEqualTo(env.meta().id());

        MessageProperties props = outbound.getMessageProperties();
        assertThat(props.getType()).isEqualTo(expect.get("urn"));
        assertThat(props.getCorrelationId()).isEqualTo(env.traceId());
        assertThat(props.getMessageId()).isEqualTo(env.meta().id());
        assertThat(props.getHeaders())
            .containsEntry("x-schema-version", ((Number) expect.get("schema_version")).intValue())
            .containsEntry("x-source-lang", expect.get("lang"))
            .containsEntry("x-attempts", ((Number) expect.get("attempts")).intValue());
    }

    private static void assertExpectations(Envelope env, Map<String, Object> expect) {
        assertThat(EnvelopeCodec.urn(env)).isEqualTo(expect.get("urn"));
        assertThat(env.attempts()).isEqualTo(((Number) expect.get("attempts")).intValue());
        assertThat(env.meta().lang()).isEqualTo(expect.get("lang"));
        assertThat(env.meta().schemaVersion()).isEqualTo(((Number) expect.get("schema_version")).intValue());

        if (expect.get("data") != null) {
            assertThat(normalize(env.data())).isEqualTo(normalize(expect.get("data")));
        }

        assertThat(env.traceId()).isNotBlank();
        assertThat(env.meta().id()).isNotBlank();
        assertThat(env.meta().createdAt()).isNotZero();

        Map<String, Object> expectedDl = expect.get("dead_letter") == null ? null : asMap(expect.get("dead_letter"));
        if (expectedDl != null) {
            assertThat(env.deadLetter()).as("expected a dead_letter block").isNotNull();
            if (expectedDl.get("reason") != null) {
                assertThat(env.deadLetter().reason()).isEqualTo(expectedDl.get("reason"));
            }
            if (expectedDl.get("original_queue") != null) {
                assertThat(env.deadLetter().originalQueue()).isEqualTo(expectedDl.get("original_queue"));
            }
        }
    }

    /** Integral numbers become {@code Long}, other numbers {@code Double}; containers are rebuilt. */
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(normalize(v)));
            return out;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) && !(n instanceof Double) && !(n instanceof Float)
                ? (Object) n.longValue()
                : (Object) d;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String readResource(String path) {
        try (InputStream in = BabelQueueConformanceTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
