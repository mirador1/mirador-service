package com.mirador.mcp;

import com.mirador.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization integration test on the MCP HTTP/SSE path.
 *
 * <p>Verifies the SecurityConfig rule {@code .requestMatchers("/sse",
 * "/sse/**", "/mcp/**").authenticated()} : an un-authenticated client
 * gets {@code 401}, an authenticated one gets through to the MCP handler.
 *
 * <p>The MCP wire protocol itself is owned by the upstream SDK so this
 * test stops at the security boundary — once Spring Security lets the
 * request through, we trust the SDK.
 */
@AutoConfigureMockMvc
class McpAuthITest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private io.fabric8.kubernetes.client.KubernetesClient kubernetesClient;

    @Test
    void unauthenticatedClientGets401OnSseEndpoint() throws Exception {
        mockMvc.perform(get("/sse"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedClientGets401OnMcpMessageEndpoint() throws Exception {
        mockMvc.perform(get("/mcp/message"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedClientPassesSecurityForSse() throws Exception {
        // The actual MCP handler may return 4xx for a malformed handshake
        // — we only assert the security filter let the request through
        // (anything OTHER than 401).
        mockMvc.perform(get("/sse")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("user").roles("USER")))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    if (sc == 401) {
                        throw new AssertionError("Security still blocked authenticated user (got 401)");
                    }
                });
    }

    @Test
    void authenticatedClientPassesSecurityForMcpMessage() throws Exception {
        mockMvc.perform(get("/mcp/message")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("user").roles("USER")))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    if (sc == 401) {
                        throw new AssertionError("Security still blocked authenticated user (got 401)");
                    }
                });
    }
}
