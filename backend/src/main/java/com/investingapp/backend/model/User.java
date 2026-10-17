// src/main/java/com/investingapp/backend/model/User.java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.LocalDate; // Import LocalDate
import java.time.Period; // Import Period for age calculation
import java.util.HashSet; // Import HashSet
import java.util.Set; // Import Set

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email")
})
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "userProgress") // Exclude userProgress to avoid circular reference
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Opaque, stable identifier for this user outside our own database (analytics identity).
     * Never the sequential id and never the email: one leaks volume, the other is PII the user can
     * change. Assigned on insert and never updated.
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false, length = 36)
    private String publicId;

    /**
     * Analytics consent, owned by the server so it is honoured on every device and by server-side
     * emission, not just by the client that set it. TRUE means opted OUT.
     *
     * New users default to opted IN (false): an opt-in default would leave every funnel built from
     * a small self-selected group. The Settings toggle and the privacy disclosure are what make
     * that fair. The client still treats consent as unknown, and so opted out, until it reads this
     * value, so a failure to read it fails closed.
     */
    @Column(name = "analytics_opt_out", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean analyticsOptOut = false;

    // @NotBlank(message = "First name cannot be blank")
    @Size(max = 50)
    private String firstName; // Optional if you only want email initially

    @Column
    private LocalDate dateOfBirth; // For age calculation

    // @NotBlank(message = "Last name cannot be blank")
    @Size(max = 50)
    private String lastName; // Optional

    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email should be valid")
    @Size(max = 100)
    @Column(unique = true, nullable = false)
    private String email; // Primary identifier

    // @NotBlank // No longer needed if no password
    // @Size(max = 100)
    // private String password; // REMOVE THIS FIELD

    // WebAuthn User Handle (can be same as ID or a separate UUID for privacy)
    // The Yubico library often uses a ByteArray for this. We can store it as a
    // string.
    @Column(unique = true, length = 255) // Must be unique if used as user handle for WebAuthn
    private String userHandle; // Store as Base64URL encoded string

    // Sensitive Action PIN
    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "failed_pin_attempts")
    private Integer failedPinAttempts = 0;

    @Column(name = "pin_lockout_until")
    private LocalDateTime pinLockoutUntil;

    @Column(name = "pin_lockout_level")
    private Integer pinLockoutLevel = 0;

    // Referral System
    @Column(unique = true, length = 20)
    private String referralCode;

    @Column(name = "has_applied_referral")
    private Boolean hasAppliedReferral = false;

    @Column(name = "referral_count")
    private Integer referralCount = 0;

    @Column(name = "referral_applied_at")
    private LocalDateTime referralAppliedAt;

    @Column(name = "subscription_started_at")
    private LocalDateTime subscriptionStartedAt;

    @Column(name = "referral_reward_triggered")
    private Boolean referralRewardTriggered = false;

    @Column(name = "referral_counted")
    private Boolean referralCounted = false;

    @Column(name = "referred_by_user_id")
    private Long referredByUserId;

    // Account Recovery
    @Column(name = "ssn", length = 512)
    private String ssn; // Stored as ENC:<base64(iv+ciphertext)> — encrypted at rest

    @Column(name = "ssn_hash", length = 64)
    private String ssnHash; // SHA-256 hex of raw SSN — used for lookup only (not decryptable)

    @Column(name = "recovery_otp", length = 512)
    private String recoveryOtp; // Stored as ENC:<base64(iv+ciphertext)> — encrypted at rest

    @Column(name = "recovery_otp_expiry")
    private LocalDateTime recoveryOtpExpiry;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<PasskeyCredential> passkeyCredentials = new HashSet<>();

    // One-to-one mapping with UserProgress entity using mappedBy to avoid foreign
    // key constraint
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private UserProgress userProgress;

    // Plaid specific fields
    @Column(length = 512)
    private String plaidAccessToken; // Stored as ENC:<base64(iv+ciphertext)> — encrypted at rest

    @Column(length = 255)
    private String plaidItemId;

    @Column(length = 255)
    private String plaidAccountId; // Primary bank account ID from Plaid

    @Column(length = 255)
    private String plaidInstitutionName; // Bank name (e.g., "Chase Bank")

    @Column(length = 255)
    private String plaidAccountName; // Account nickname (e.g., "Chase Checking")

    @Column(length = 50)
    private String plaidAccountType; // "depository", "credit", etc.

    @Column(length = 50)
    private String plaidAccountSubtype; // "checking", "savings", etc.

    // Alpaca ACH relationship fields
    @Column(length = 512)
    private String alpacaAccountId; // Stored as ENC:<base64(iv+ciphertext)> — encrypted at rest

    @Column(length = 512)
    private String alpacaAccountNumber; // Stored as ENC:<base64(iv+ciphertext)> — encrypted at rest

    @Column(length = 512)
    private String alpacaAchRelationshipId; // Stored as ENC:<base64(iv+ciphertext)> — encrypted at rest

    @Column(length = 50)
    private String alpacaAchStatus; // "QUEUED", "APPROVED", "PENDING", etc.

    @Column(name = "account_status", length = 30)
    private String accountStatus; // SUBMITTED, APPROVAL_PENDING, APPROVED, ACTIVE, REJECTED, ACTION_REQUIRED

    // IP address tracking for security purposes
    @Column(length = 45) // IPv6 addresses can be up to 45 characters
    private String registrationIpAddress;

    // Device ID tracking for better user experience (more reliable than IP)
    @Column(length = 64) // Device ID hash, typically 32-64 characters
    private String deviceId;

    private String planId;
    private String timeToFI;
    private Double targetPortfolio;
    private Double retirementIncome;
    private Double monthlyInvestment;

    // Monthly Freedom Update fields
    @Column(name = "last_logged_in_month")
    private String lastLoggedInMonth; // Format: "YYYY-MM"

    @Column(name = "user_account_length")
    private Integer userAccountLength; // Number of months the user has had an account

    @Column(name = "current_freedom_estimate")
    private Integer currentFreedomEstimate; // Year the user is projected to reach freedom

    @Column(name = "previous_freedom_estimate")
    private Integer previousFreedomEstimate; // Freedom estimate from 12 months ago

    @Column(name = "recurring_investment_count")
    private Integer recurringInvestmentCount; // Count of completed recurring executions

    @Column(name = "current_streak")
    private Integer currentStreak; // Consecutive months with active (non-paused) investment schedule

    @Column(name = "last_streak_update")
    private String lastStreakUpdate; // "YYYY-MM" of last streak increment to prevent double-counting

    @Column(name = "milestone_history", columnDefinition = "TEXT")
    private String milestoneHistory; // JSON string of achieved milestones e.g. ["INVESTMENT_10","EQUITY_1K"]

    @Column(name = "last_mfu_period_start")
    private String lastMfuPeriodStart; // ISO date "yyyy-MM-dd" of last MFU period start

    @Column(name = "last_mfu_period_end")
    private String lastMfuPeriodEnd; // ISO date "yyyy-MM-dd" of last MFU period end

    @Column(name = "last_mfu_milestones", columnDefinition = "TEXT")
    private String lastMfuMilestones; // JSON of milestones shown in last MFU e.g. [{"type":"...","label":"...","subtitle":"..."}]

    @Column(name = "mfu_count")
    private Integer mfuCount; // Number of unique (non-reopen) MFUs the user has seen

    // DRIP (Dividend Reinvestment Plan)
    @Column(name = "drip_enabled")
    private Boolean dripEnabled = true;

    // Investment scheduling fields
    @Column(length = 20)
    private String payFrequency; // "weekly", "biweekly", "monthly", "semimonthly"

    @Column
    private java.time.LocalDate nextInvestmentDate; // When the next investment should be executed

    @Column(length = 50)
    private String selectedStrategy; // "optimal", "balanced", "adaptive", "safety"

    @Column(name = "selected_tier", nullable = true, length = 10)
    private String selectedTier; // "core", "plus", "pro"

    @Column(name = "billing_period", nullable = true, length = 10)
    private String billingPeriod; // "monthly", "yearly"

    @Column(name = "private_beta", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean privateBeta = false;

    // Tax Loss Harvesting (Piggy Pro) — explicit opt-in on top of tier;
    // tier alone never triggers selling
    @Column(name = "tlh_enabled", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean tlhEnabled = false;

    @Column(name = "tlh_enrolled_at")
    private LocalDateTime tlhEnrolledAt;

    @Column(name = "subscription_start_date", nullable = true)
    private java.time.LocalDate subscriptionStartDate;

    public java.time.LocalDate getSubscriptionStartDate() { return subscriptionStartDate; }
    public void setSubscriptionStartDate(java.time.LocalDate subscriptionStartDate) { this.subscriptionStartDate = subscriptionStartDate; }

    @Column(name = "device_push_token", length = 512, nullable = true)
    private String devicePushToken;

    public String getDevicePushToken() { return devicePushToken; }
    public void setDevicePushToken(String devicePushToken) { this.devicePushToken = devicePushToken; }

    @Column(name = "agreed_to_marketing", nullable = true)
    private Boolean agreedToMarketing;

    public Boolean getAgreedToMarketing() { return agreedToMarketing; }
    public void setAgreedToMarketing(Boolean agreedToMarketing) { this.agreedToMarketing = agreedToMarketing; }

    // Helper method to get Plaid relationship ID (using existing ACH relationship
    // field)
    public String getPlaidRelationshipId() {
        return this.alpacaAchRelationshipId;
    }

    public void setPlaidRelationshipId(String relationshipId) {
        this.alpacaAchRelationshipId = relationshipId;
    }

    // Helper to get Age from DOB
    public Integer getAge() {
        if (this.dateOfBirth == null)
            return null;
        return Period.between(this.dateOfBirth, LocalDate.now()).getYears();
    }

    // Helper to derive Risk Tolerance from Strategy
    public String getRiskTolerance() {
        if (this.selectedStrategy == null)
            return "medium"; // Default

        switch (this.selectedStrategy.toLowerCase()) {
            case "safety":
                return "low";
            case "balanced":
                return "medium";
            case "optimal":
            case "adaptive":
                return "high";
            default:
                return "medium";
        }
    }

    @CreationTimestamp // Automatically set by Hibernate on creation
    private LocalDateTime createDate;

    @UpdateTimestamp // Automatically set by Hibernate on update
    private LocalDateTime updateDate;

    /**
     * Every row gets a public_id, whichever path created it. The column is NOT NULL, so a missing
     * value fails the insert rather than producing a user with no analytics identity.
     */
    @PrePersist
    private void assignPublicId() {
        if (this.publicId == null || this.publicId.isBlank()) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
    }

    public User(String email) { // Simplified constructor for passkey registration
        this.email = email;
        // Generate a user handle, e.g., from UUID or a transformation of the user ID
        // after first save
        // For now, it can be set later or derived.

        // Set default investment settings
        this.monthlyInvestment = 0.0;
        this.payFrequency = "monthly";
        this.selectedStrategy = "balanced";
        this.dripEnabled = true;

        // Don't create UserProgress here - handle in service layer to avoid circular
        // reference
    }

    // public User(String firstName, String lastName, String email) {
    // this.firstName = firstName;
    // this.lastName = lastName;
    // this.email = email;
    // }

    public UserProgress getUserProgress() {
        return userProgress;
    }

    public void setUserProgress(UserProgress userProgress) {
        this.userProgress = userProgress;
        if (userProgress != null) {
            userProgress.setUser(this);
        }
    }

    // Custom equals and hashCode to avoid circular reference
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        User user = (User) o;
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}