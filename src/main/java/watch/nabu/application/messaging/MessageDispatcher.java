package watch.nabu.application.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes messages to the appropriate channel-specific sender.
 * Spring auto-injects all {@link MessageSenderPort} implementations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDispatcher {

    private final List<MessageSenderPort> senders;

    public void dispatch(Message message) {
        MessageSenderPort sender = senders.stream()
                .filter(s -> s.supports(message.channel()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "No sender registered for channel: " + message.channel()));

        log.info("[messaging] Dispatching {} to {}", message.channel(), message.recipient());
        sender.send(message);
    }
}
