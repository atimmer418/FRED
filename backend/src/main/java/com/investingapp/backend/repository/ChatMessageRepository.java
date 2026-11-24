package com.investingapp.backend.repository;

import com.investingapp.backend.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SECURITY: every lookup here takes the user id. A session id is chosen by the app, so it is not a
 * secret and two users can hold the same one (everyone who sends none gets "default-session"). A
 * conversation is therefore identified by user AND session, never by the session id alone. Do not
 * add a method that finds messages by session id only.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** The newest ten messages of one user's conversation, newest first. */
    List<ChatMessage> findTop10ByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, String sessionId);

    /** One user's whole conversation, newest first. */
    List<ChatMessage> findByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, String sessionId);

    /** The user's most recent message in any conversation. */
    Optional<ChatMessage> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    /** The first message with the given role in one user's conversation. */
    Optional<ChatMessage> findTopByUserIdAndSessionIdAndRoleOrderByCreatedAtAsc(
            Long userId, String sessionId, String role);
}
