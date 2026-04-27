package com.mirador.mcp;

import com.mirador.mcp.actuator.ActuatorService;
import com.mirador.mcp.domain.ChaosToolService;
import com.mirador.mcp.domain.CustomerToolService;
import com.mirador.mcp.domain.OrderToolService;
import com.mirador.mcp.domain.ProductToolService;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mirador.mcp.logs.LogbackRingBufferAppender;
import com.mirador.mcp.logs.LogsService;
import com.mirador.mcp.metrics.MetricsService;
import com.mirador.mcp.openapi.OpenApiService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * MCP server wiring : registers an explicit allowlist of services as the
 * source of {@code @Tool}-annotated methods.
 *
 * <p>Per ADR-0062 § per-method opt-in : we use
 * {@link MethodToolCallbackProvider#builder().toolObjects(...)} with the
 * exact list of services we trust to expose tools. The alternative —
 * {@code .beanFactory(bf).all()} — would auto-include every {@code @Tool}-
 * annotated method on every Spring bean in the context, including future
 * ones added by transitive dependencies (Spring Boot starters, Spring AI
 * itself, …) ; that's the loss-of-control mode the ADR rejects.
 *
 * <p>The 10 services registered below cover the 15-tool catalogue :
 * <ul>
 *   <li>4 domain : {@link OrderToolService} (4 tools), {@link ProductToolService}
 *       (1 tool), {@link CustomerToolService} (1 tool), {@link ChaosToolService}
 *       (1 tool) — total 7 domain tools.</li>
 *   <li>4 backend-local observability : {@link LogsService} (1 tool),
 *       {@link MetricsService} (1 tool), {@link ActuatorService} (4 tools :
 *       health summary / detail / env / info), {@link OpenApiService}
 *       (1 tool) — total 7 observability tools.</li>
 *   <li>1 ML inference : {@code ChurnMcpToolService} (1 tool —
 *       {@code predict_customer_churn}) — added per shared ADR-0061
 *       Phase B (Customer Churn ONNX inference in-process via
 *       {@code com.mirador.ml.ChurnPredictor}).</li>
 * </ul>
 *
 * <h3>What this configuration does NOT do</h3>
 * <p>It does NOT instantiate any HTTP client for Loki / Mimir / Grafana /
 * GitLab / GitHub. Per ADR-0062 the Mirador backend stays infrastructure-
 * agnostic ; external infra MCP servers are SEPARATE community servers
 * that each developer adds via {@code claude mcp add}.
 */
@Configuration
public class McpConfig {

    /**
     * The ring-buffer appender bean — registered here so {@link LogsService}
     * and {@code RingBufferAppenderRegistration} share the same instance.
     * Without this central definition the appender becomes a candidate for
     * Spring component scan auto-detection in unintended places.
     *
     * @return appender with the {@link LogbackRingBufferAppender#DEFAULT_CAPACITY}
     */
    @Bean
    public LogbackRingBufferAppender ringBufferAppender() {
        return new LogbackRingBufferAppender();
    }

    /**
     * Customizer that registers the {@code mcp-metrics} cache in the
     * Spring-Boot-auto-configured {@link CaffeineCacheManager} with a
     * dedicated 5-second TTL.
     *
     * <p>The application's main cache spec in {@code application.yml} is
     * 5 minutes (right for {@code customer-by-id} entity reads but too
     * long for live metric samples) and applies uniformly via a single
     * Caffeine spec. Adding a dedicated builder for our cache via
     * {@link CaffeineCacheManager#registerCustomCache} preserves the
     * single-manager topology (no composite, no @Primary override) while
     * giving us per-cache control of the TTL.
     *
     * <p>Spring Boot 4 calls {@code afterPropertiesSet} on the bean
     * before this method's bean returns, so the registration sticks.
     */
    @Bean
    public org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer<CaffeineCacheManager>
        mcpMetricsCacheCustomizer() {
        return cacheManager -> cacheManager.registerCustomCache(
                MetricsService.CACHE_NAME,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(5))
                        .maximumSize(256)
                        .build());
    }

    /**
     * The MCP {@link ToolCallbackProvider} consumed by the Spring AI
     * starter — auto-wires the HTTP/SSE transport and surfaces the
     * registered tool objects at the configured MCP endpoint paths.
     *
     * @param orderTool      4 order tools
     * @param productTool    1 product tool
     * @param customerTool   1 customer tool (360-aggregate)
     * @param chaosTool      1 chaos tool (admin)
     * @param logsService    1 log tool
     * @param metricsService 1 metrics tool
     * @param actuatorService 4 actuator tools (health x 2, env, info)
     * @param openApiService 1 OpenAPI tool
     * @return provider that registers all 15 tools at startup
     */
    @Bean
    public ToolCallbackProvider miradorToolProvider(
            OrderToolService orderTool,
            ProductToolService productTool,
            CustomerToolService customerTool,
            ChaosToolService chaosTool,
            LogsService logsService,
            MetricsService metricsService,
            ActuatorService actuatorService,
            OpenApiService openApiService,
            com.mirador.ml.ChurnMcpToolService churnTool
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        orderTool,
                        productTool,
                        customerTool,
                        chaosTool,
                        logsService,
                        metricsService,
                        actuatorService,
                        openApiService,
                        churnTool
                )
                .build();
    }
}
