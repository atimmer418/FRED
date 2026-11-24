package com.investingapp.backend.repository;

import com.investingapp.backend.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    List<UserSession> findByUserId(Long userId);
    List<UserSession> findByUserIdAndActiveTrue(Long userId);
    java.util.Optional<UserSession> findByUserIdAndDeviceId(Long userId, String deviceId);
    void deleteAllByUserId(Long userId);

    /** Ends every active session a user has (account recovery). Call inside a transaction. */
    @Modifying
    @Query("update UserSession s set s.active = false where s.user.id = :userId and s.active = true")
    int deactivateAllForUser(@Param("userId") Long userId);
}
