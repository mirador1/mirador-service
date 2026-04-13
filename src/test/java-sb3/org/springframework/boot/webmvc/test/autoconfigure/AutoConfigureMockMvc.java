package org.springframework.boot.webmvc.test.autoconfigure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bridge annotation for Spring Boot 3 compatibility.
 *
 * <p>In Spring Boot 4, {@code @AutoConfigureMockMvc} lives in the
 * {@code org.springframework.boot.webmvc.test.autoconfigure} package. In Spring Boot 3,
 * it lives in {@code org.springframework.boot.test.autoconfigure.web.servlet}.
 *
 * <p>This bridge annotation provides the SB4 package path and delegates to the real SB3
 * annotation via meta-annotation, so existing test files that import from the SB4 package
 * compile and work correctly under Spring Boot 3 without any modification.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
public @interface AutoConfigureMockMvc {
}
