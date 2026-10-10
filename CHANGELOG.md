# Changelog

All notable changes to `com.babelqueue:babelqueue-spring` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The envelope wire format is versioned separately by `meta.schema_version`
(currently **1**) — see the contract at [babelqueue.com](https://babelqueue.com).

## [Unreleased]

## [1.1.1] - 2026-10-10

### Changed
- **Cross-SDK conformance (GR-5).** The canonical `conformance/` suite (v1.0.0) is vendored under
  `src/test/resources/conformance` and `BabelQueueConformanceTest` drives the manifest's `cases`, `roundtrip`,
  `data_shape`, `forbidden_keys` and `rabbitmq.property_projection` sections through
  `BabelQueueMessageConverter` (`fromMessage` accept/reject, `fromMessage` -> `toMessage` unknown-key
  preservation, `toMessage` re-emit + the full AMQP property/header projection). CI gains a `conformance` drift-guard job and `ci-green` now requires it. Test-only; no
  change to the public API or the wire envelope.

### Fixed
- **Dependency floor:** `babelqueue-core` raised to **1.8.0** (was 1.5.0, the out-of-band header-carrier seam). 1.8.0 preserves
  unknown top-level and `meta` envelope keys on re-emit (forward compatibility, GR-5); with 1.5.0 the converter dropped them.

## [1.1.0] - 2026-06-21

### Added
- **OpenTelemetry `traceparent` transport wiring (ADR-0028, v0.2).**
  `BabelQueuePublisher` gains `publishWithHeaders(Envelope, Map<String,String>)` — the
  produce-side seam the optional core `com.babelqueue.otel.HeaderSender` wires to: it sends
  through a `MessagePostProcessor` that writes out-of-band headers (e.g. a W3C
  `traceparent`) onto the AMQP `MessageProperties` headers **beside** the contract `x-*`
  headers (`SpringHeaders.apply`: a contract header always wins a key collision), never
  inside the frozen envelope (GR-1). New `SpringHeaders.of(Message)` /
  `of(MessageProperties)` surface a delivered message's headers as a `Map<String,String>`,
  the consume-side seam a `@RabbitListener` wires to `Tracing.wrapHandler(tracer, handler,
  Supplier)`, so a carried `traceparent` makes the consumer span a true child of the
  producer span. A header-less publish is equivalent to before; `trace_id` is preserved
  (GR-4); `schema_version` stays **1**. No new runtime dependency — the header seam is a
  plain `Map<String,String>`; OpenTelemetry is needed only by callers who opt in.

## [1.0.0] - 2026-06-07

**1.0.0 — the public API is now SemVer-stable**: breaking changes require a MAJOR,
following the deprecation policy. The wire envelope is unchanged
(`schema_version: 1`). Full reference at [babelqueue.com](https://babelqueue.com).

### Changed
- Require `com.babelqueue:babelqueue-core 1.0.0`.

### Internal
- Build adds **JaCoCo** (line-coverage gate ≥90%, bound to `verify`) and
  **SpotBugs** (`effort=Max`, `threshold=Medium`, `spotbugs-exclude.xml` for the
  read-only-envelope EI_EXPOSE patterns); both run in CI via `mvn verify`. Added a
  `BabelQueuePublisher(PolyglotMessage)` test to clear the gate.

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

[Unreleased]: https://github.com/BabelQueue/babelqueue-spring/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/BabelQueue/babelqueue-spring/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/BabelQueue/babelqueue-spring/releases/tag/v0.1.0
