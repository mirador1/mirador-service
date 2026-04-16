/**
 * Overlay for {@link com.mirador.observability} — Java &lt;25 variant.
 *
 * <p>This folder is NOT compiled in the default build. It contains alternate
 * source files that replace their {@code src/main/java} counterparts when
 * the Maven {@code compat} or {@code sb3} profile is active (see
 * {@code pom.xml} → {@code maven-antrun-plugin/compat-merge-sources}).
 *
 * <p>Reason: Java 25 introduced {@code ScopedValue} as a replacement for
 * {@code ThreadLocal}. The production code in {@code src/main/java} uses
 * {@code ScopedValue}; when the target JVM is 21 or 17, those files are
 * swapped for the ones here, which use {@code ThreadLocal} and drop the
 * {@code ScopedValue} imports entirely.
 *
 * <p>Files overridden by this overlay (always — no conditional):
 * <ul>
 *   <li>{@code RequestIdFilter.java}</li>
 *   <li>{@code RequestContext.java}</li>
 *   <li>{@code TraceService.java}</li>
 * </ul>
 *
 * <p>Keep the public API identical to the base versions — only the internal
 * implementation changes. Otherwise classes in other packages compiling
 * against these types will break depending on which overlay is active.
 */
package com.mirador.observability;
