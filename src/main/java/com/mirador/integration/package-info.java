/**
 * Outbound HTTP integrations with external services.
 *
 * <p>This package groups the classes that call external APIs. Each integration
 * is isolated behind a service interface so the rest of the codebase never
 * touches {@code RestClient} or {@code WebClient} directly — the domain layer
 * calls {@code BioService.fetchBio(email)}, and this package handles retries,
 * timeouts, circuit-breakers, and response mapping.
 *
 * <dl>
 *   <dt>{@link HttpClientConfig}</dt>
 *   <dd>Shared {@code RestClient} and {@code WebClient} beans with sensible
 *       defaults: connect + read timeouts, trace-context propagation (W3C
 *       Baggage), and Micrometer metrics for every outbound call.</dd>
 *
 *   <dt>{@link JsonPlaceholderClient}</dt>
 *   <dd>Thin wrapper around <a href="https://jsonplaceholder.typicode.com">jsonplaceholder.typicode.com</a>,
 *       a free REST API used to simulate a real upstream. All public methods
 *       return {@code Mono} / {@code Optional} so callers never see a raw
 *       HTTP exception.</dd>
 *
 *   <dt>{@link BioService}</dt>
 *   <dd>Public-facing service: fetches the user's bio from the upstream. Wraps
 *       {@link JsonPlaceholderClient} with Resilience4J (circuit breaker + retry).</dd>
 *
 *   <dt>{@link TodoService} / {@link TodoItem}</dt>
 *   <dd>Fetches per-user task lists from the same upstream. Used to demonstrate
 *       parallel external calls and timeline aggregation.</dd>
 * </dl>
 *
 * <h2>Resilience policy</h2>
 * <ul>
 *   <li><b>Timeout</b>: 2 seconds per call. Above that, the upstream is
 *       considered unhealthy and the circuit breaker opens.</li>
 *   <li><b>Retries</b>: 2 (3 total attempts), only on idempotent methods, with
 *       exponential backoff.</li>
 *   <li><b>Circuit breaker</b>: opens after 50% failure rate over 10 calls,
 *       half-opens 30 s later.</li>
 * </ul>
 *
 * <p>All these values are set declaratively in {@code application.yml} under
 * {@code resilience4j.*} — they are NOT hard-coded here.
 */
package com.mirador.integration;
