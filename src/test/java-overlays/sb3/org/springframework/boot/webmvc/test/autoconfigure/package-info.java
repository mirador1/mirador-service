/**
 * Test overlay — stubs a Spring Boot 4–only annotation for SB3 compatibility.
 *
 * <p>This package does NOT belong to the mirador codebase; the path matches
 * {@code org.springframework.boot.webmvc.test.autoconfigure} to shadow the
 * real Spring annotation {@code @AutoConfigureMockMvc} in Spring Boot 4.
 *
 * <p>The test classes in {@code src/test/java/com/mirador/**} import
 * {@link org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc}
 * (the SB4 location). In SB3, that package does NOT exist — the annotation
 * lives at {@code org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc}.
 * This overlay bridges the gap with a thin re-export annotation so the
 * same test source compiles under both Spring Boot major versions without
 * duplicating the test files.
 *
 * <p>This overlay is copied into the test compile source root by the
 * {@code sb3} Maven profile (see {@code pom.xml} → {@code compat-merge-sources-test}).
 * Under SB4, the overlay is NOT applied — the real SB4 annotation is used directly.
 *
 * <p>Files in this overlay:
 * <ul>
 *   <li>{@code AutoConfigureMockMvc.java} — thin re-export annotation.</li>
 * </ul>
 */
package org.springframework.boot.webmvc.test.autoconfigure;
