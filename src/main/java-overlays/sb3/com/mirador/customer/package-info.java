/**
 * Overlay for {@link com.mirador.customer} — Spring Boot 3 variant.
 *
 * <p>This folder is NOT compiled in the default build. Sources here replace
 * their {@code src/main/java} counterparts when {@code -Dsb3} is active (see
 * {@code pom.xml} → the {@code sb3} profile, which imports the Spring Boot 3
 * BOM and applies this overlay).
 *
 * <p>Reason: Spring Boot 4 added native API versioning via
 * {@code @GetMapping(version="1.0")}. That attribute does not exist in
 * Spring Boot 3. The overlay replaces {@code CustomerController.java} with
 * a version that dispatches on an {@code X-API-Version} header manually —
 * functionally identical to clients, but implemented with SB3-available APIs.
 *
 * <p>Files overridden by this overlay (always, under {@code -Dsb3}):
 * <ul>
 *   <li>{@code CustomerController.java}</li>
 * </ul>
 *
 * <p>The sb3 profile ALSO activates the {@code pre-java25} overlay (SB3
 * requires Java &lt;25), so a Spring Boot 3 build gets both this overlay
 * and the {@code ScopedValue → ThreadLocal} swap. If {@code -Djava17} is
 * also set, the Java-17 overlay stacks on top as well.
 */
package com.mirador.customer;
