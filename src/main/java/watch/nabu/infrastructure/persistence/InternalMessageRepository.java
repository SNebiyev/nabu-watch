package watch.nabu.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.InternalMessage;

import java.util.UUID;

public interface InternalMessageRepository extends JpaRepository<InternalMessage, UUID> {

    /**
     * All messages received by the given user, newest-first.
     */
    Page<InternalMessage> findByReceiverIdOrderByCreatedAtDesc(String receiverId, Pageable pageable);

    /**
     * All messages sent by the given user, newest-first.
     */
    Page<InternalMessage> findBySenderIdOrderByCreatedAtDesc(String senderId, Pageable pageable);

    /**
     * Number of distinct senders who have sent at least one unread message to
     * this user.  Used for the inbox unread-badge (shows unique senders, not
     * raw message count).
     */
    @Query("SELECT COUNT(DISTINCT m.senderId) FROM InternalMessage m " +
            "WHERE m.receiverId = :receiverId AND m.isRead = false")
    int countUnreadDistinctSenders(@Param("receiverId") String receiverId);

    /**
     * Marks all unread messages from a specific sender as read in one UPDATE.
     */
    @Modifying
    @Transactional
    @Query("UPDATE InternalMessage m SET m.isRead = true " +
            "WHERE m.receiverId = :receiverId AND m.senderId = :senderId AND m.isRead = false")
    int markConversationAsRead(@Param("receiverId") String receiverId,
                               @Param("senderId") String senderId);

    @Modifying
    @Query(value = "DELETE FROM internal_messages WHERE sender_id = :email OR receiver_id = :email", nativeQuery = true)
    int deleteBySenderOrReceiver(@Param("email") String email);
}
