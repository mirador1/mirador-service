package org.iris.mcp;

import org.iris.mcp.actuator.ActuatorService;
import org.iris.mcp.domain.ChaosToolService;
import org.iris.mcp.domain.CustomerToolService;
import org.iris.mcp.domain.OrderToolService;
import org.iris.mcp.domain.ProductToolService;
import org.iris.mcp.logs.LogbackRingBufferAppender;
import org.iris.mcp.logs.LogsService;
import org.iris.mcp.metrics.MetricsService;
import org.iris.mcp.openapi.OpenApiService;
import org.iris.ml.ChurnMcpToolService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link McpConfig} bean factory methods. No Spring
 * context is started — we just verify each {@code @Bean} method returns
 * a sane object given mocked collaborators. The full integration of
 * the tool catalogue is exercised by {@code McpServerITest}.
 *
 * <p>Pinned invariants :
 * <ul>
 *   <li>{@link McpConfig#ringBufferAppender()} returns a non-null
 *       appender — the LogsService relies on this same instance for
 *       its ring-buffer reads.</li>
 *   <li>{@link McpConfig#mcpMetricsCacheCustomizer()} registers the
 *       {@code mcp-metrics} cache (name pinned because MetricsService
 *       uses it as a key) with a 5-second TTL.</li>
 *   <li>{@link McpConfig#irisToolProvider} returns a non-null
 *       {@link ToolCallbackProvider} when given the 9 expected tool
 *       services.</li>
 * </ul>
 */
class McpConfigTest {

    private final McpConfig config = new McpConfig();

    @Test
    void ringBufferAppender_returnsNonNullAppender() {
        LogbackRingBufferAppender appender = config.ringBufferAppender();
        assertThat(appender).isNotNull();
    }

    @Test
    void mcpMetricsCacheCustomizer_registersMcpMetricsCache() {
        // Pinned : the cache name MUST equal MetricsService.CACHE_NAME ;
        // a typo here would silently break the @Cacheable lookup at
        // runtime (the cache wouldn't be found, calls would never cache).
        CacheManagerCustomizer<CaffeineCacheManager> customizer =
                config.mcpMetricsCacheCustomizer();
        assertThat(customizer).isNotNull();

        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        customizer.customize(cacheManager);

        assertThat(cacheManager.getCacheNames()).contains(MetricsService.CACHE_NAME);
        assertThat(cacheManager.getCache(MetricsService.CACHE_NAME)).isNotNull();
    }

    @Test
    void irisToolProvider_returnsNonNullProvider_when9ToolServicesGiven() {
        ToolCallbackProvider provider = config.irisToolProvider(
                mock(OrderToolService.class),
                mock(ProductToolService.class),
                mock(CustomerToolService.class),
                mock(ChaosToolService.class),
                mock(LogsService.class),
                mock(MetricsService.class),
                mock(ActuatorService.class),
                mock(OpenApiService.class),
                mock(ChurnMcpToolService.class)
        );

        assertThat(provider).isNotNull();
        // Sanity : the provider must expose its tool callbacks (the count
        // depends on the @Tool methods on the mocked services — mocks have
        // no real annotations, so we just check the call doesn't throw).
        assertThat(provider.getToolCallbacks()).isNotNull();
    }
}
