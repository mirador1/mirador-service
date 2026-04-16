/**
 * Overlay for {@link com.mirador.api} — Java 17 variant.
 *
 * <p>This folder is NOT compiled in the default build. Sources here replace
 * their {@code src/main/java} counterparts when BOTH {@code -Dcompat} AND
 * {@code -Djava17} are active (see {@code pom.xml} → the {@code if:set="java17"}
 * conditional copy in the {@code maven-antrun-plugin/compat-merge-sources}
 * execution).
 *
 * <p>Reason: Java 21 introduced pattern matching for {@code switch} with
 * {@code when} guards (JEP 441), and the production code in
 * {@code src/main/java/com/mirador/api/ApiExceptionHandler.java} uses that
 * syntax for its dispatch on exception subtypes. Java 17 parses it as
 * {@code caseIllegalStateExceptionewhen} — unrecognised. The overlay
 * replaces the switch with an equivalent {@code if/else if} ladder.
 *
 * <p>Files overridden by this overlay (only when {@code -Djava17}):
 * <ul>
 *   <li>{@code ApiExceptionHandler.java}</li>
 * </ul>
 *
 * <p>The pre-java25 overlay (for {@code ScopedValue}) is orthogonal and
 * stacks on top — a Java 17 build gets BOTH overlays applied.
 */
package com.mirador.api;
