// src/main/java/com/investingapp/backend/repository/PasskeyCredentialRepository.java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PasskeyCredential;
import com.investingapp.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {
    Optional<PasskeyCredential> findByExternalId(String externalId);

    /** How many passkeys a user has. */
    long countByUser_Id(Long userId);
    List<PasskeyCredential> findAllByUser(User user);
    List<PasskeyCredential> findAllByUser_UserHandle(String userHandle); // Find by user's WebAuthn handle
}