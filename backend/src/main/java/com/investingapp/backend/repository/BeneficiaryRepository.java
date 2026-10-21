package com.investingapp.backend.repository;

import com.investingapp.backend.model.Beneficiary;
import com.investingapp.backend.model.Beneficiary.BeneficiaryStatus;
import com.investingapp.backend.model.Beneficiary.BeneficiaryType;
import com.investingapp.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing beneficiary data
 */
@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    
    /**
     * Find all beneficiaries for a specific user
     */
    List<Beneficiary> findByUserIdOrderByBeneficiaryTypeAscPercentageAllocationDesc(Long userId);
    
    /**
     * Find all active beneficiaries for a specific user
     */
    List<Beneficiary> findByUserIdAndStatusOrderByBeneficiaryTypeAscPercentageAllocationDesc(
        Long userId, BeneficiaryStatus status);
    
    /**
     * Find all beneficiaries by type for a specific user
     */
    List<Beneficiary> findByUserIdAndBeneficiaryTypeOrderByPercentageAllocationDesc(
        Long userId, BeneficiaryType beneficiaryType);
    
    /**
     * Find all primary beneficiaries for a specific user
     */
    @Query("SELECT b FROM Beneficiary b WHERE b.user.id = :userId AND b.beneficiaryType = 'PRIMARY' ORDER BY b.percentageAllocation DESC")
    List<Beneficiary> findPrimaryBeneficiariesByUserId(@Param("userId") Long userId);
    
    /**
     * Find all contingent beneficiaries for a specific user
     */
    @Query("SELECT b FROM Beneficiary b WHERE b.user.id = :userId AND b.beneficiaryType = 'CONTINGENT' ORDER BY b.percentageAllocation DESC")
    List<Beneficiary> findContingentBeneficiariesByUserId(@Param("userId") Long userId);
    
    /**
     * Calculate total allocation percentage for a specific user and beneficiary type
     */
    @Query("SELECT COALESCE(SUM(b.percentageAllocation), 0) FROM Beneficiary b WHERE b.user.id = :userId AND b.beneficiaryType = :beneficiaryType AND b.status != 'INACTIVE'")
    BigDecimal calculateTotalAllocationByUserAndType(@Param("userId") Long userId, @Param("beneficiaryType") BeneficiaryType beneficiaryType);
    
    /**
     * Calculate total allocation percentage for all beneficiaries of a specific user
     */
    @Query("SELECT COALESCE(SUM(b.percentageAllocation), 0) FROM Beneficiary b WHERE b.user.id = :userId AND b.status != 'INACTIVE'")
    BigDecimal calculateTotalAllocationByUser(@Param("userId") Long userId);
    
    /**
     * Find beneficiaries that need to be submitted to Alpaca
     */
    List<Beneficiary> findByStatusOrderByCreatedAtAsc(BeneficiaryStatus status);
    
    /**
     * Find beneficiary by Alpaca beneficiary ID
     */
    Optional<Beneficiary> findByAlpacaBeneficiaryId(String alpacaBeneficiaryId);
    
    /**
     * Check if user has any active beneficiaries
     */
    @Query("SELECT COUNT(b) > 0 FROM Beneficiary b WHERE b.user.id = :userId AND b.status = 'APPROVED'")
    boolean hasActiveBeneficiaries(@Param("userId") Long userId);
    
    /**
     * Count beneficiaries by type for a specific user
     */
    @Query("SELECT COUNT(b) FROM Beneficiary b WHERE b.user.id = :userId AND b.beneficiaryType = :beneficiaryType AND b.status != 'INACTIVE'")
    long countByUserIdAndBeneficiaryType(@Param("userId") Long userId, @Param("beneficiaryType") BeneficiaryType beneficiaryType);
    
    /**
     * Find beneficiaries with SSN (for compliance checks)
     */
    @Query("SELECT b FROM Beneficiary b WHERE b.user.id = :userId AND b.socialSecurityNumber IS NOT NULL AND b.socialSecurityNumber != ''")
    List<Beneficiary> findBeneficiariesWithSSN(@Param("userId") Long userId);
    
    /**
     * Find beneficiaries missing required information
     */
    @Query("SELECT b FROM Beneficiary b WHERE b.user.id = :userId AND " +
           "(b.firstName IS NULL OR b.firstName = '' OR " +
           "b.lastName IS NULL OR b.lastName = '' OR " +
           "b.dateOfBirth IS NULL OR " +
           "b.addressLine1 IS NULL OR b.addressLine1 = '' OR " +
           "b.city IS NULL OR b.city = '' OR " +
           "b.state IS NULL OR b.state = '' OR " +
           "b.postalCode IS NULL OR b.postalCode = '')")
    List<Beneficiary> findBeneficiariesWithMissingInfo(@Param("userId") Long userId);
    
    /**
     * Find duplicate beneficiaries (same name and DOB)
     */
    @Query("SELECT b FROM Beneficiary b WHERE b.user.id = :userId AND " +
           "b.firstName = :firstName AND b.lastName = :lastName AND " +
           "b.dateOfBirth = :dateOfBirth AND b.id != :excludeId")
    List<Beneficiary> findDuplicateBeneficiaries(@Param("userId") Long userId,
                                                 @Param("firstName") String firstName,
                                                 @Param("lastName") String lastName,
                                                 @Param("dateOfBirth") java.time.LocalDate dateOfBirth,
                                                 @Param("excludeId") Long excludeId);

    /** User deletion: beneficiaries.user_id has no ON DELETE CASCADE (V1:40). */
    void deleteAllByUser(User user);
}
