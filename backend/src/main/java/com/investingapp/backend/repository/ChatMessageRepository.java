package com.investingapp.backend.repository;

import com.investingapp.backend.model.ChatMessage;
// Correct
import org.springframework.data.jpa.repository.JpaRepository; // Correct
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // findTop10BySessionIdOrderByCreatedAtDesc usually works with Spring Data JPA
    // derived queries
    List<ChatMessage> findTop10BySessionIdOrderByCreatedAtDesc(String sessionId);

    List<ChatMessage> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    Optional<ChatMessage> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ChatMessage> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    Optional<ChatMessage> findTopBySessionIdAndRoleOrderByCreatedAtAsc(String sessionId, String role);

    // If query method name is too long or unsupported without explicit limit in
    // some versions:
    // @Query(...) or use Pageable
    // But TopN is standard.
}
