package com.investingapp.backend.controller;

import com.investingapp.backend.model.Beneficiary;
import com.investingapp.backend.model.Beneficiary.BeneficiaryStatus;
import com.investingapp.backend.model.Beneficiary.BeneficiaryType;
import com.investingapp.backend.model.Beneficiary.Relationship;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.BeneficiaryRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.BeneficiaryService;
import com.investingapp.backend.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * User A is signed in. Beneficiary 77 belongs to user B. Nothing A sends may read, change, remove
 * or submit it, and A must get the same 404 as for an id that does not exist.
 *
 * The real controller and the real service run here; only the repository and the broker are mocks.
 * The repository mock answers the way the database does: the owner-scoped lookup finds B's row only
 * for B. If the service ever goes back to the id-only lookup (findById), these tests fail, because
 * that lookup is never stubbed and is asserted unused.
 */
class BeneficiaryControllerOwnershipTest {

    private static final Long USER_A_ID = 1L;
    private static final Long USER_B_ID = 2L;
    private static final Long B_BENEFICIARY_ID = 77L;
    private static final String USER_A_EMAIL = "user.a@fredvested.test";

    private BeneficiaryRepository beneficiaryRepository;
    private AlpacaService alpacaService;
    private BeneficiaryController controller;
    private Authentication userA;
    private Beneficiary bRow;

    @BeforeEach
    void setUp() {
        beneficiaryRepository = mock(BeneficiaryRepository.class);
        alpacaService = mock(AlpacaService.class);
        UserRepository userRepository = mock(UserRepository.class);

        BeneficiaryService service = new BeneficiaryService();
        ReflectionTestUtils.setField(service, "beneficiaryRepository", beneficiaryRepository);
        ReflectionTestUtils.setField(service, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(service, "encryptionService", mock(EncryptionService.class));

        controller = new BeneficiaryController();
        ReflectionTestUtils.setField(controller, "beneficiaryService", service);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        User a = new User();
        a.setId(USER_A_ID);
        a.setEmail(USER_A_EMAIL);
        a.setAlpacaAccountId("ENC:account-a");
        when(userRepository.findByEmail(USER_A_EMAIL)).thenReturn(Optional.of(a));
        userA = new UsernamePasswordAuthenticationToken(USER_A_EMAIL, null, List.of());

        User b = new User();
        b.setId(USER_B_ID);
        b.setEmail("user.b@fredvested.test");
        bRow = beneficiary("Bea", "Owner");
        bRow.setId(B_BENEFICIARY_ID);
        bRow.setUser(b);

        // The database's answer: B's row exists, but not for A.
        when(beneficiaryRepository.findByIdAndUserId(B_BENEFICIARY_ID, USER_B_ID)).thenReturn(Optional.of(bRow));
        when(beneficiaryRepository.findByIdAndUserId(B_BENEFICIARY_ID, USER_A_ID)).thenReturn(Optional.empty());
        when(beneficiaryRepository.calculateTotalAllocationByUserAndType(anyLong(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(beneficiaryRepository.save(any(Beneficiary.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Beneficiary beneficiary(String first, String last) {
        Beneficiary b = new Beneficiary();
        b.setBeneficiaryType(BeneficiaryType.PRIMARY);
        b.setFirstName(first);
        b.setLastName(last);
        b.setDateOfBirth(LocalDate.of(1990, 1, 1));
        b.setSocialSecurityNumber("123-45-6789");
        b.setPercentageAllocation(new BigDecimal("50.00"));
        b.setRelationship(Relationship.SIBLING);
        return b;
    }

    private void assertBRowUntouched() {
        assertThat(bRow.getFirstName()).isEqualTo("Bea");
        assertThat(bRow.getUser().getId()).isEqualTo(USER_B_ID);
        assertThat(bRow.getStatus()).isEqualTo(BeneficiaryStatus.PENDING);
        verify(beneficiaryRepository, never()).save(bRow);
        verify(beneficiaryRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("user A cannot change user B's beneficiary: 404 and nothing is saved")
    void cannotUpdateAnotherUsersBeneficiary() {
        ResponseEntity<?> response =
                controller.updateBeneficiary(B_BENEFICIARY_ID, beneficiary("Mallory", "Attacker"), userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(beneficiaryRepository, never()).save(any());
        assertBRowUntouched();
    }

    @Test
    @DisplayName("user A cannot delete user B's beneficiary: 404, nothing saved, broker not called")
    void cannotDeleteAnotherUsersBeneficiary() {
        ResponseEntity<?> response = controller.deleteBeneficiary(B_BENEFICIARY_ID, userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(beneficiaryRepository, never()).save(any());
        verifyNoInteractions(alpacaService);
        assertBRowUntouched();
    }

    @Test
    @DisplayName("user A cannot submit user B's beneficiary to the broker: 404, and B's email is not leaked")
    void cannotSubmitAnotherUsersBeneficiary() {
        ResponseEntity<?> response = controller.submitToAlpaca(B_BENEFICIARY_ID, userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((BeneficiaryController.ErrorResponse) response.getBody()).getError())
                .isEqualTo("Beneficiary not found")
                .doesNotContain("user.b@fredvested.test");
        verifyNoInteractions(alpacaService);
        assertBRowUntouched();
    }

    @Test
    @DisplayName("an id that does not exist gets the same 404 as someone else's id")
    void missingIdLooksTheSameAsSomeoneElsesId() {
        when(beneficiaryRepository.findByIdAndUserId(999L, USER_A_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> missing = controller.deleteBeneficiary(999L, userA);
        ResponseEntity<?> notMine = controller.deleteBeneficiary(B_BENEFICIARY_ID, userA);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notMine.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((BeneficiaryController.ErrorResponse) missing.getBody()).getError())
                .isEqualTo(((BeneficiaryController.ErrorResponse) notMine.getBody()).getError());
    }

    @Test
    @DisplayName("a create that carries user B's row id makes a new row for A and does not take B's")
    void createIgnoresAClientSuppliedId() {
        Beneficiary body = beneficiary("Mallory", "Attacker");
        body.setId(B_BENEFICIARY_ID);

        ResponseEntity<?> response = controller.createBeneficiary(body, userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Beneficiary> saved = ArgumentCaptor.forClass(Beneficiary.class);
        verify(beneficiaryRepository).save(saved.capture());
        // A null id is what makes save() an INSERT. With B's id it would have been an UPDATE of B's row.
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getUser().getId()).isEqualTo(USER_A_ID);
        assertBRowUntouched();
    }

    @Test
    @DisplayName("the owner can still change and delete their own beneficiary")
    void ownerStillCan() {
        Beneficiary own = beneficiary("Ada", "Mine");
        own.setId(5L);
        User a = new User();
        a.setId(USER_A_ID);
        own.setUser(a);
        when(beneficiaryRepository.findByIdAndUserId(5L, USER_A_ID)).thenReturn(Optional.of(own));
        when(beneficiaryRepository.findById(5L)).thenReturn(Optional.of(own));

        ResponseEntity<?> updated = controller.updateBeneficiary(5L, beneficiary("Ada", "Renamed"), userA);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getLastName()).isEqualTo("Renamed");

        ResponseEntity<?> deleted = controller.deleteBeneficiary(5L, userA);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getStatus()).isEqualTo(BeneficiaryStatus.INACTIVE);
    }
}
