package com.investingapp.backend.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.investingapp.backend.model.converter.EncryptedStringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing account beneficiaries for Transfer on Death (TOD) designations
 * Beneficiaries inherit account assets upon the account holder's death
 */
@Entity
@Table(name = "beneficiaries")
public class Beneficiary {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;
    
    // Beneficiary Type: PRIMARY or CONTINGENT
    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type", nullable = false)
    @NotNull(message = "Beneficiary type is required")
    private BeneficiaryType beneficiaryType;
    
    // Personal Information
    @Column(name = "first_name", nullable = false, length = 100)
    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must be 100 characters or less")
    private String firstName;
    
    @Column(name = "last_name", nullable = false, length = 100)
    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must be 100 characters or less")
    private String lastName;
    
    @Column(name = "email", length = 255)
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must be 255 characters or less")
    private String email;
    
    @Column(name = "phone", length = 20)
    @Pattern(regexp = "^[+]?[0-9\\s\\-\\(\\)]+$", message = "Phone number must be valid")
    @Size(max = 20, message = "Phone number must be 20 characters or less")
    private String phone;
    
    @Column(name = "date_of_birth", nullable = false)
    @NotNull(message = "Date of birth is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;
    
    /**
     * The full number, as typed: 123-45-6789. It belongs to someone who is not our user.
     *
     * In the database it is always ciphertext (the converter encrypts on the way in and decrypts on
     * the way out), so this field holds the readable number only while a request is being handled.
     * It is write-only in JSON: a client can send it, and no response ever carries it. Responses
     * carry {@link #getSsnLast4()} instead. The broker needs the full number every time the list is
     * sent, which is why it is kept at all.
     *
     * Not marked required here because an edit may leave it out, meaning "keep the one on file".
     * BeneficiaryService insists on it for a new beneficiary, and the column is NOT NULL.
     */
    @Column(name = "social_security_number", length = 255, nullable = false)
    @Convert(converter = EncryptedStringConverter.class)
    @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{4}$", message = "SSN must be in format XXX-XX-XXXX")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String socialSecurityNumber;
    
    // Address Information
    @Column(name = "address_line1", length = 255)
    @Size(max = 255, message = "Address line 1 must be 255 characters or less")
    private String addressLine1;
    
    @Column(name = "address_line2", length = 255)
    @Size(max = 255, message = "Address line 2 must be 255 characters or less")
    private String addressLine2;
    
    @Column(name = "city", length = 100)
    @Size(max = 100, message = "City must be 100 characters or less")
    private String city;
    
    @Column(name = "state", length = 2)
    @Pattern(regexp = "^[A-Z]{2}$", message = "State must be 2 uppercase letters")
    private String state;
    
    @Column(name = "postal_code", length = 10)
    @Pattern(regexp = "^\\d{5}(-\\d{4})?$", message = "Postal code must be in format XXXXX or XXXXX-XXXX")
    private String postalCode;
    
    @Column(name = "country", length = 2)
    @Pattern(regexp = "^[A-Z]{2}$", message = "Country must be 2 uppercase letters")
    private String country = "US"; // Default to US
    
    // Beneficiary Allocation
    @Column(name = "percentage_allocation", nullable = false, precision = 5, scale = 2)
    @NotNull(message = "Percentage allocation is required")
    @DecimalMin(value = "0.01", message = "Percentage allocation must be at least 0.01%")
    @DecimalMax(value = "100.00", message = "Percentage allocation cannot exceed 100%")
    private BigDecimal percentageAllocation;
    
    // Relationship to Account Holder
    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false)
    @NotNull(message = "Relationship is required")
    private Relationship relationship;
    
    // Status and Processing
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BeneficiaryStatus status = BeneficiaryStatus.PENDING;
    
    @Column(name = "alpaca_beneficiary_id", length = 100)
    private String alpacaBeneficiaryId; // ID from Alpaca's system if available
    
    @Column(name = "notes", length = 1000)
    @Size(max = 1000, message = "Notes must be 1000 characters or less")
    private String notes;
    
    // Audit Fields
    @Column(name = "created_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
    
    @Column(name = "submitted_to_alpaca_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submittedToAlpacaAt;
    
    @Column(name = "approved_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime approvedAt;
    
    // Constructors
    public Beneficiary() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = BeneficiaryStatus.PENDING;
        this.country = "US";
    }
    
    public Beneficiary(User user, BeneficiaryType beneficiaryType, String firstName, String lastName, 
                       BigDecimal percentageAllocation, Relationship relationship) {
        this();
        this.user = user;
        this.beneficiaryType = beneficiaryType;
        this.firstName = firstName;
        this.lastName = lastName;
        this.percentageAllocation = percentageAllocation;
        this.relationship = relationship;
    }
    
    // JPA Lifecycle Methods
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
    
    // Enums
    public enum BeneficiaryType {
        PRIMARY("Primary beneficiary - first in line to inherit"),
        CONTINGENT("Contingent beneficiary - inherits if primary is unavailable");
        
        private final String description;
        
        BeneficiaryType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public enum Relationship {
        SPOUSE("Spouse"),
        CHILD("Child"),
        PARENT("Parent"),
        SIBLING("Sibling"),
        GRANDCHILD("Grandchild"),
        GRANDPARENT("Grandparent"),
        AUNT_UNCLE("Aunt/Uncle"),
        NIECE_NEPHEW("Niece/Nephew"),
        COUSIN("Cousin"),
        FRIEND("Friend"),
        DOMESTIC_PARTNER("Domestic Partner"),
        TRUST("Trust"),
        ESTATE("Estate"),
        CHARITY("Charity/Organization"),
        OTHER("Other");
        
        private final String displayName;
        
        Relationship(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    public enum BeneficiaryStatus {
        PENDING("Pending submission to broker"),
        SUBMITTED("Submitted to broker for review"),
        APPROVED("Approved and active"),
        REJECTED("Rejected by broker"),
        INACTIVE("Inactive/Removed");
        
        private final String description;
        
        BeneficiaryStatus(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    // Helper Methods
    public String getFullName() {
        return firstName + " " + lastName;
    }
    
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getFullAddress() {
        StringBuilder address = new StringBuilder();
        if (addressLine1 != null && !addressLine1.trim().isEmpty()) {
            address.append(addressLine1);
        }
        if (addressLine2 != null && !addressLine2.trim().isEmpty()) {
            if (address.length() > 0) address.append(", ");
            address.append(addressLine2);
        }
        if (city != null && !city.trim().isEmpty()) {
            if (address.length() > 0) address.append(", ");
            address.append(city);
        }
        if (state != null && !state.trim().isEmpty()) {
            if (address.length() > 0) address.append(", ");
            address.append(state);
        }
        if (postalCode != null && !postalCode.trim().isEmpty()) {
            if (address.length() > 0) address.append(" ");
            address.append(postalCode);
        }
        return address.toString();
    }
    
    public boolean isPrimary() {
        return BeneficiaryType.PRIMARY.equals(beneficiaryType);
    }
    
    public boolean isContingent() {
        return BeneficiaryType.CONTINGENT.equals(beneficiaryType);
    }
    
    public boolean isActive() {
        return BeneficiaryStatus.APPROVED.equals(status);
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    
    public BeneficiaryType getBeneficiaryType() { return beneficiaryType; }
    public void setBeneficiaryType(BeneficiaryType beneficiaryType) { this.beneficiaryType = beneficiaryType; }
    
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    
    public String getSocialSecurityNumber() { return socialSecurityNumber; }
    /** A blank number is no number: an edit that sends "" keeps the one on file, like one that sends nothing. */
    public void setSocialSecurityNumber(String socialSecurityNumber) {
        this.socialSecurityNumber =
                socialSecurityNumber == null || socialSecurityNumber.isBlank() ? null : socialSecurityNumber;
    }

    /**
     * The last four digits, the only part of the number a response may carry. Enough for the owner
     * to recognise which number is on file. Null when there is no number.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getSsnLast4() {
        if (socialSecurityNumber == null) {
            return null;
        }
        String digits = socialSecurityNumber.replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }
    
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }
    
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
    
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    
    public BigDecimal getPercentageAllocation() { return percentageAllocation; }
    public void setPercentageAllocation(BigDecimal percentageAllocation) { this.percentageAllocation = percentageAllocation; }
    
    public Relationship getRelationship() { return relationship; }
    public void setRelationship(Relationship relationship) { this.relationship = relationship; }
    
    public BeneficiaryStatus getStatus() { return status; }
    public void setStatus(BeneficiaryStatus status) { this.status = status; }
    
    public String getAlpacaBeneficiaryId() { return alpacaBeneficiaryId; }
    public void setAlpacaBeneficiaryId(String alpacaBeneficiaryId) { this.alpacaBeneficiaryId = alpacaBeneficiaryId; }
    
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public LocalDateTime getSubmittedToAlpacaAt() { return submittedToAlpacaAt; }
    public void setSubmittedToAlpacaAt(LocalDateTime submittedToAlpacaAt) { this.submittedToAlpacaAt = submittedToAlpacaAt; }
    
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
}
