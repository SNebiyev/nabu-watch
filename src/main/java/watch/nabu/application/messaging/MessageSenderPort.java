package watch.nabu.application.messaging;

/**
 * Port for sending messages through a specific channel.
 * Implement this interface to add new delivery channels (SMS, push, etc.).
 */
public interface MessageSenderPort {
    void send(Message message);

    boolean supports(Message.Channel channel);
}
