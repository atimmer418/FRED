// src/main/java/com/investingapp/backend/model/PasskeyCredential.java
package com.investingapp.backend.model;

import com.yubico.webauthn.data.ByteArray; // Yubico's ByteArray
import com.yubico.webauthn.data.exception.Base64UrlException;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "passkey_credentials")
@Data
@NoArgsConstructor
public class PasskeyCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore // never written into a response; see User
    private User user;

    @Column(nullable = false, unique = true, length = 512) // Credential ID from the authenticator
    @com.fasterxml.jackson.annotation.JsonIgnore // internal; responses use the DTOs in dto/
    private String externalId; // Store as Base64URL encoded string

    @Column(nullable = false, length = 1024) // Public key from the authenticator
    @com.fasterxml.jackson.annotation.JsonIgnore // internal; never written into a response
    private String publicKeyCose; // Store as Base64URL encoded string

    @Column(nullable = false)
    private Long signatureCount; // From the authenticator, for detecting clones

    @Column(length = 255)
    private String userAgent; // User agent of the browser/OS during registration (optional, for audit)

    @Column(length = 100)
    private String credentialType; // e.g., "public-key"

    @CreationTimestamp
    private LocalDateTime createdDate;

    private LocalDateTime lastUsedDate;

    @Column(length = 100)
    private String friendlyName; // e.g., "My iPhone", "Chrome on MacBook" (user can set this)

    public PasskeyCredential(User user, String externalId, String publicKeyCose, Long signatureCount, String credentialType) {
        this.user = user;
        this.externalId = externalId;
        this.publicKeyCose = publicKeyCose;
        this.signatureCount = signatureCount;
        this.credentialType = credentialType;
    }

    // Helper to convert Yubico's ByteArray to Base64URL string for storage
    public static String byteArrayToBase64Url(ByteArray byteArray) {
        return byteArray.getBase64Url();
    }

    // Helper to convert Base64URL string from DB back to Yubico's ByteArray
    public static ByteArray base64UrlToByteArray(String base64Url) {
        try {
         return ByteArray.fromBase64Url(base64Url);
     } catch (Base64UrlException e) {
         // Handle this appropriately - maybe log and throw an unchecked exception
         // or return a default/empty ByteArray if that makes sense in your context, though usually an error.
         throw new IllegalArgumentException("Invalid Base64URL string for ByteArray: " + base64Url, e);
     }
    }
}