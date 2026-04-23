package com.mirador.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebSocketConfig} — pin the STOMP broker prefix
 * + endpoint configuration that the Angular UI depends on.
 *
 * <p>Pinned contracts:
 *   - SimpleBroker enabled on {@code /topic} (UI subscribes to
 *     {@code /topic/customers})
 *   - Application destinations prefixed with {@code /app}
 *   - STOMP endpoint at {@code /ws} with SockJS fallback
 *   - Origin allowlist = {@code *} (dev-mode tolerance — production
 *     would tighten this to specific origins)
 */
class WebSocketConfigTest {

    private final WebSocketConfig config = new WebSocketConfig();

    @Test
    void configureMessageBroker_enablesSimpleBrokerOnSlashTopic() {
        // Pinned: the UI subscribes to /topic/customers — switching the
        // prefix to /queue or /broker would silently break every WebSocket
        // listener on the frontend (no error, just no events received).
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
    }

    @Test
    void configureMessageBroker_setsApplicationDestinationPrefixToSlashApp() {
        // Pinned: client → server messages must be prefixed /app for
        // STOMP to route them to @MessageMapping handlers. Changing this
        // prefix would silently drop every inbound STOMP frame.
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void registerStompEndpoints_registersSlashWsEndpoint() {
        // Pinned: the SockJS client URL is hardcoded as ws://host/ws on
        // the Angular side. A path change here without a coordinated UI
        // change breaks the connection silently — the dashboard would
        // show "WebSocket disconnected" until both repos are updated.
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpointReg =
                mock(StompWebSocketEndpointRegistration.class);
        SockJsServiceRegistration sockJsReg = mock(SockJsServiceRegistration.class);

        when(registry.addEndpoint("/ws")).thenReturn(endpointReg);
        when(endpointReg.setAllowedOriginPatterns("*")).thenReturn(endpointReg);
        when(endpointReg.withSockJS()).thenReturn(sockJsReg);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
    }

    @Test
    void registerStompEndpoints_allowsAnyOriginAndEnablesSockJs() {
        // Pinned: setAllowedOriginPatterns("*") — dev-mode tolerance so
        // the UI can connect from localhost:4200 (Angular dev server)
        // OR localhost:8080 (served from the JAR). Production would
        // narrow this to a specific origin allowlist; flagged as a
        // hardening item but not a blocker for the demo.
        // SockJS fallback — required for browsers that don't support
        // WebSocket directly (very rare today, but the SockJS client
        // library still expects this on the server side).
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpointReg =
                mock(StompWebSocketEndpointRegistration.class);
        SockJsServiceRegistration sockJsReg = mock(SockJsServiceRegistration.class);

        when(registry.addEndpoint("/ws")).thenReturn(endpointReg);
        when(endpointReg.setAllowedOriginPatterns("*")).thenReturn(endpointReg);
        when(endpointReg.withSockJS()).thenReturn(sockJsReg);

        config.registerStompEndpoints(registry);

        verify(endpointReg).setAllowedOriginPatterns("*");
        verify(endpointReg, times(1)).withSockJS();
    }
}
