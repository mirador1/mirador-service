package com.example.customerservice.messaging;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket configuration for real-time customer notifications.
 *
 * <p>Clients connect via SockJS at {@code /ws} and subscribe to:
 * <ul>
 *   <li>{@code /topic/customers} — receives a message each time a customer is created</li>
 * </ul>
 *
 * <p>The Angular UI can subscribe with:
 * <pre>{@code
 * const socket = new SockJS('http://localhost:8080/ws');
 * const stompClient = Stomp.over(socket);
 * stompClient.subscribe('/topic/customers', msg => console.log(JSON.parse(msg.body)));
 * }</pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
