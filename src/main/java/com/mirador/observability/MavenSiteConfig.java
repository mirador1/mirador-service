package com.mirador.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the Maven site HTML from target/site/ at /maven-site/ during local development.
 *
 * Run `mvn site` first to generate the reports, then access them at:
 *   http://localhost:8080/maven-site/index.html
 *
 * In CI, the maven-site job generates and publishes the site as a pipeline artifact.
 * If embedded static resources exist at classpath:/static/maven-site/, Spring Boot
 * will serve them automatically (the classpath takes precedence over this handler).
 */
@Configuration
public class MavenSiteConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve generated Maven site at /maven-site/ from the local build output.
        // The classpath location (classpath:/static/maven-site/) takes precedence when the
        // site is embedded in the JAR (e.g., after running `mvn site` in CI).
        registry.addResourceHandler("/maven-site/**")
                .addResourceLocations(
                        "classpath:/static/maven-site/",  // embedded (production)
                        "file:target/site/"               // local dev fallback
                );
    }
}
