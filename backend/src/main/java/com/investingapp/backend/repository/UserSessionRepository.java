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
    void deleteAllByUserId(Long userId);

    /** Ends every active session a user has (account recovery). Call inside a transaction. */
    @Modifying
    @Query("update UserSession s set s.active = false where s.user.id = :userId and s.active = true")
    int deactivateAllForUser(@Param("userId") Long userId);

    /**
     * Removes the sessions a new sign-in makes pointless: the user's sessions on the same device
     * (when the device is known), any of theirs that were ended, and any that started so long ago
     * that their tokens have run out. A token naming a removed session is refused exactly like one
     * naming an ended session, and the table holds only sessions that can still be in use.
     *
     * @param deviceId the device signing in, or null when it sent no id (then no row matches on device)
     * @param startedBefore sessions started before this moment are past their end
     */
    @Modifying
    @Query("delete from UserSession s where s.user.id = :userId "
            + "and (s.active = false or s.deviceId = :deviceId or s.lastActive < :startedBefore)")
    int deleteReplacedEndedAndExpired(@Param("userId") Long userId, @Param("deviceId") String deviceId,
            @Param("startedBefore") java.time.LocalDateTime startedBefore);

    boolean existsByUserId(Long userId);

    /** True when this session exists, is active and belongs to this user. */
    boolean existsByIdAndUserIdAndActiveTrue(Long id, Long userId);
}
