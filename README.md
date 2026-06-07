# BabelQueue for Spring

[![CI](https://github.com/BabelQueue/babelqueue-spring/actions/workflows/ci.yml/badge.svg)](https://github.com/BabelQueue/babelqueue-spring/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.babelqueue/babelqueue-spring.svg)](https://central.sonatype.com/artifact/com.babelqueue/babelqueue-spring)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

> **Polyglot Queues, Simplified.** A Spring Boot adapter that makes your Spring
> services produce and consume the canonical BabelQueue message envelope over
> RabbitMQ — so they exchange messages with Laravel, Symfony, Python, Go, Node and
> .NET over one strict JSON format.

This is the **Spring adapter** on top of the framework-agnostic
[`babelqueue-core`](https://github.com/BabelQueue/babelqueue-java): a Spring AMQP
`MessageConverter`, an ergonomic publisher, and Spring Boot auto-configuration. The
full standard is documented at **[babelqueue.com](https://babelqueue.com)**.

## Installation

Maven:

```xml
<dependency>
    <groupId>com.babelqueue</groupId>
    <artifactId>babelqueue-spring</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.babelqueue:babelqueue-spring:1.0.0")
```

Bring your own Spring AMQP (the adapter targets **Spring Boot 3**, Java **17+**):

```kotlin
implementation("org.springframework.boot:spring-boot-starter-amqp")
```

## How it works

Auto-configuration registers a single `MessageConverter` bean
(`BabelQueueMessageConverter`). Spring Boot wires it into **both** the
`RabbitTemplate` (producing) and the default `@RabbitListener` container factory
(consuming), so the canonical envelope is the wire format with no extra setup. Both
beans back off if your application defines its own.

## Producing

```java
import com.babelqueue.spring.BabelQueuePublisher;
import java.util.Map;

@Service
class Orders {
    private final BabelQueuePublisher babelQueue;

    Orders(BabelQueuePublisher babelQueue) {
        this.babelQueue = babelQueue;
    }

    void create() {
        babelQueue.publish("urn:babel:orders:created", Map.of("order_id", 1042L), "orders");
    }
}
```

Or build a typed message by implementing `com.babelqueue.PolyglotMessage` and
`babelQueue.publish(message, "orders")`. Either way the broker receives:

```json
{
  "job": "urn:babel:orders:created",
  "trace_id": "…",
  "data": { "order_id": 1042 },
  "meta": { "id": "…", "queue": "orders", "lang": "java", "schema_version": 1, "created_at": 1749132727000 },
  "attempts": 0
}
```

…plus the contract AMQP properties (`type` = URN, `correlation_id` = trace_id,
`message_id` = meta.id, `x-schema-version` / `x-source-lang` / `x-attempts`).

## Consuming

A `@RabbitListener` method receives the decoded `Envelope` — from any producer, in
any language:

```java
import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;

@Component
class OrderListener {
    @RabbitListener(queues = "orders")
    void onMessage(Envelope envelope) {
        switch (EnvelopeCodec.urn(envelope)) {
            case "urn:babel:orders:created" ->
                log.info("[{}] order {}", envelope.traceId(), envelope.data().get("order_id"));
            default -> { /* ignore */ }
        }
    }
}
```

Non-conformant messages (missing URN, unsupported `meta.schema_version`, blank
`trace_id`, missing `data`) raise a `MessageConversionException`, so Spring rejects
them — route them to a dead-letter exchange the usual Spring AMQP way.

## Configuration

```yaml
babelqueue:
  default-queue: orders   # used by the publisher when no queue is given
```

## License

[MIT](LICENSE) © Muhammet Şafak
