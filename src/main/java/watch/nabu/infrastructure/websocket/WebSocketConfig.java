package watch.nabu.infrastructure.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures the STOMP-over-WebSocket message broker for real-time
 * notifications.
 *
 * <h3>Endpoint</h3>
 * <ul>
 *   <li>{@code /ws-notifications} — native WebSocket + SockJS fallback</li>
 * </ul>
 *
 * <h3>Destinations</h3>
 * <ul>
 *   <li>{@code /app/**}           — client → server (application prefix)</li>
 *   <li>{@code /queue/**}         — user-scoped (server → specific client)</li>
 *   <li>{@code /topic/**}         — broadcast (server → all subscribers)</li>
 * </ul>
 *
 * <h3>Security</h3>
 * The WebSocket HTTP handshake ({@code /ws-notifications/**}) is permitted in
 * {@link watch.nabu.infrastructure.security.SecurityConfig}.  Actual STOMP
 * authentication is enforced by {@link JwtChannelInterceptor} on the
 * {@code CONNECT} frame — no CONNECT succeeds without a valid Bearer JWT.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;

    @Value("${nabu.frontend-url}")
    private String frontendUrl;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker handles /queue (user-scoped) and /topic (broadcast)
        registry.enableSimpleBroker("/queue", "/topic");
        // Prefix for messages routed to @MessageMapping methods
        registry.setApplicationDestinationPrefixes("/app");
        // Prefix used by convertAndSendToUser() to build user-specific destinations
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-notifications")
                .setAllowedOrigins(frontendUrl)
                .withSockJS(); // SockJS fallback for browsers without native WS
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
