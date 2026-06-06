/**
 * Spring Boot adapter for BabelQueue — "Polyglot Queues, Simplified".
 *
 * <p>It binds the framework-agnostic {@link com.babelqueue Java core} to Spring AMQP
 * so a Spring service produces and consumes the canonical BabelQueue wire envelope
 * over RabbitMQ, interoperating with the PHP/Laravel, Python, Go, Node and .NET
 * SDKs. The pieces:
 *
 * <ul>
 *   <li>{@link com.babelqueue.spring.BabelQueueMessageConverter} — the Spring AMQP
 *       {@code MessageConverter} that encodes/decodes the canonical envelope.</li>
 *   <li>{@link com.babelqueue.spring.BabelQueuePublisher} — an ergonomic producer
 *       over {@code RabbitTemplate}.</li>
 *   <li>{@link com.babelqueue.spring.BabelQueueAutoConfiguration} — wires it into a
 *       Spring Boot app automatically.</li>
 * </ul>
 *
 * <p>Full spec: <a href="https://babelqueue.com">babelqueue.com</a>
 */
package com.babelqueue.spring;
