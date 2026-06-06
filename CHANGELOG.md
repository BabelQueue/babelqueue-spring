# Changelog

All notable changes to `com.babelqueue:babelqueue-spring` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The envelope wire format is versioned separately by `meta.schema_version`
(currently **1**) — see the contract at [babelqueue.com](https://babelqueue.com).

## [Unreleased]

## [0.1.0] - 2026-06-06

### Added
- `BabelQueueMessageConverter` — a Spring AMQP `MessageConverter` that encodes the
  canonical BabelQueue envelope on produce (with the contract AMQP properties:
  `type` = URN, `correlationId` = trace_id, `messageId` = meta.id,
  `x-schema-version` / `x-source-lang` / `x-attempts`) and decodes it on consume,
  returning a core `Envelope`. Rejects non-conformant messages.
- `BabelQueuePublisher` — ergonomic producer over `RabbitTemplate`
  (`publish(urn, data, queue)` / `publish(PolyglotMessage)`).
- `BabelQueueAutoConfiguration` — Spring Boot auto-configuration exposing the
  converter (so Boot wires it into the `RabbitTemplate` and listener factory) and
  the publisher; both back off if the application defines its own.
- `BabelQueueProperties` — `babelqueue.default-queue`.

### Notes
- Pre-1.0: the public API may change before the `1.0.0` tag.
- Built on the framework-agnostic `com.babelqueue:babelqueue-core`; targets Spring
  Boot **3** and Java **17+**.

[Unreleased]: https://github.com/BabelQueue/babelqueue-spring/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/BabelQueue/babelqueue-spring/releases/tag/v0.1.0
