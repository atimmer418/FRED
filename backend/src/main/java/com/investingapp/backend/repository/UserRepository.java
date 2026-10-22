// src/main/java/com/investingapp/backend/repository/UserRepository.java
package com.investingapp.backend.repository; // Make sure this package name matches

import com.investingapp.backend.model.User; // Import your User entity
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Spring Data JPA will automatically implement methods based on their names:

    // Find a user by their email address
    // Returns an Optional, which can be empty if no user is found
    Optional<User> findByEmail(String email);

    Optional<User> findByReferralCode(String referralCode);
    boolean existsByReferralCode(String referralCode);

    Optional<User> findBySsnHash(String ssnHash);

    // Find a user by email with UserProgress eagerly fetched (avoids lazy loading
    // cache issues)
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.userProgress WHERE u.email = :email")
    Optional<User> findByEmailWithProgress(@Param("email") String email);

    // Check if a user exists with the given email address
    Boolean existsByEmail(String email);

    // Find a user by their WebAuthn user handle (for passkey authentication)
    Optional<User> findByUserHandle(String userHandle);

    // Find a user by their Plaid item ID for webhook processing
    User findByPlaidItemId(String plaidItemId);

    // Every user holding a Plaid access token: the one-off /item/webhook/update backfill
    // (PlaidItemLifecycleService.backfillWebhookUrl, exposed by DevPlaidController)
    List<User> findByPlaidAccessTokenIsNotNull();

    // Find users by registration IP who have completed auth-finalize (for passkey
    // re-auth prompting)
    @Query("SELECT u FROM User u JOIN u.userProgress up WHERE u.registrationIpAddress = :ipAddress AND up.authFinalizeCompleted = :completed")
    List<User> findByRegistrationIpAddressAndAuthFinalizeCompleted(@Param("ipAddress") String registrationIpAddress,
            @Param("completed") boolean authFinalizeCompleted);

    // Find users by device ID who have completed auth-finalize (more reliable than
    // IP for mobile)
    @Query("SELECT u FROM User u JOIN u.userProgress up WHERE u.deviceId = :deviceId AND up.authFinalizeCompleted = :completed")
    List<User> findByDeviceIdAndAuthFinalizeCompleted(@Param("deviceId") String deviceId,
            @Param("completed") boolean authFinalizeCompleted);

    // Find users whose account status is non-terminal (i.e., not ACTIVE or REJECTED)
    // and non-null — used by the account status poller.
    @Query("SELECT u FROM User u WHERE u.accountStatus IS NOT NULL AND u.accountStatus NOT IN ('ACTIVE', 'REJECTED')")
    List<User> findUsersWithPendingAccountStatus();

    // Find ACTIVE users who have Plaid data but no ACH relationship yet —
    // used by the ACH setup retry scheduler.
    @Query("SELECT u FROM User u WHERE u.accountStatus = 'ACTIVE' AND u.alpacaAccountId IS NOT NULL AND u.plaidAccessToken IS NOT NULL AND u.alpacaAchRelationshipId IS NULL")
    List<User> findActiveUsersNeedingAchSetup();

    // Paid-tier members eligible for the Monthly Market Breakdown email
    List<User> findBySelectedTierIn(java.util.Collection<String> tiers);

    // Piggy Pro members who explicitly opted into Tax Loss Harvesting —
    // tier AND enrollment enforced in the query itself (scheduler gating idiom)
    List<User> findBySelectedTierInAndTlhEnabledTrue(java.util.Collection<String> tiers);

    // Load a user row under a pessimistic write-lock (SELECT ... FOR UPDATE) so concurrent Monthly
    // Freedom Update "seen" commits (double-tap, two tabs, two devices) serialize on the row and the
    // advances-only guard in MonthlyFreedomUpdateService.commitMfuSeen can enforce exactly-once
    // (no double mfuCount++ / double freedom-estimate rotation).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // Same row lock as findByIdForUpdate, but fails fast instead of waiting: the Plaid exchange
    // holds this lock across outbound Plaid HTTP calls, so a contended second exchange must not
    // block for the default lock-wait timeout and surface as a 500. A lock timeout of 0 renders
    // "FOR UPDATE NOWAIT" on MySQL 8/Hibernate, so a contended lock raises immediately (translated
    // by Spring to PessimisticLockingFailureException) and the caller maps it to LINK_IN_PROGRESS.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdateNoWait(@Param("id") Long id);

    // You can add more custom query methods here as needed following Spring Data
    // JPA conventions
    // e.g., List<User> findByLastName(String lastName);
}