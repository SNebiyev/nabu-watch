package watch.nabu.infrastructure.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import watch.nabu.infrastructure.security.JwtService;
import watch.nabu.infrastructure.security.NabuUserDetailsService;

/**
 * STOMP channel interceptor that authenticates the WebSocket connection
 * on the {@code CONNECT} frame using the existing JWT infrastructure.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The frontend sends the JWT as a STOMP header:
 *       {@code Authorization: Bearer <token>}</li>
 *   <li>On {@code CONNECT}, this interceptor extracts and validates the
 *       token using {@link JwtService}.</li>
 *   <li>If valid, a {@link UsernamePasswordAuthenticationToken} is set as
 *       the STOMP session principal.  {@code SimpMessagingTemplate}
 *       uses this principal to route {@code /user/**} destinations.</li>
 *   <li>If invalid or absent, a {@link MessagingException} is thrown,
 *       which closes the WebSocket connection with an error frame.</li>
 * </ol>
 *
 * <p>Frames other than {@code CONNECT} are passed through unchanged —
 * the principal set during handshake persists for the lifetime of the
 * STOMP session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final NabuUserDetailsService userDetailsService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message; // only authenticate on CONNECT
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[ws-auth] CONNECT rejected — missing or malformed Authorization header");
            throw new MessagingException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        String email;
        try {
            email = jwtService.extractSubject(token);
        } catch (Exception e) {
            log.warn("[ws-auth] CONNECT rejected — cannot parse JWT: {}", e.getMessage());
            throw new MessagingException("Invalid JWT token");
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        if (!jwtService.isValid(token, userDetails)) {
            log.warn("[ws-auth] CONNECT rejected — JWT validation failed for email={}", email);
            throw new MessagingException("JWT token is expired or invalid");
        }

        // Set the authenticated principal on the STOMP session.
        // SimpMessagingTemplate.convertAndSendToUser() uses principal.getName()
        // (the email) to compute the user-specific destination.
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        accessor.setUser(auth);

        log.debug("[ws-auth] CONNECT authenticated: email={}", email);
        return message;
    }
}
