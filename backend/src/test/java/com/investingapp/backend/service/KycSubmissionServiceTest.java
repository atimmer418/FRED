package com.investingapp.backend.service;

import com.investingapp.backend.dto.CreateAlpacaAccountRequest;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What kyc_submitted carries, and when it is not sent at all.
 *
 * The property map is asserted whole, not by key: a subset assertion would pass just as happily if
 * somebody later added the email, the legal name or the raw attempt count beside the band, which is
 * the exact failure this event is designed to prevent.
 */
@ExtendWith(MockitoExtension.class)
class KycSubmissionServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private AnalyticsOutboxService analyticsOutboxService;

    @InjectMocks
    private KycSubmissionService service;

    @Captor
    private ArgumentCaptor<Map<String, Object>> propertiesCaptor;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("ada@fredvested.com");
        user.setId(USER_ID);
    }

    private static Map<String, Object> brokerResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account_id", "alpaca-account-id");
        result.put("account_number", "alpaca-account-number");
        return result;
    }

    private static CreateAlpacaAccountRequest request() {
        CreateAlpacaAccountRequest request = new CreateAlpacaAccountRequest();
        request.setEmailAddress("ada@fredvested.com");
        request.setGivenName("Ada");
        request.setFamilyName("Lovelace");
        request.setTaxId("000-00-0000");
        return request;
    }

    /** The whole captured map, for the one event the service is allowed to record. */
    private Map<String, Object> recordedProperties() {
        verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.KYC_SUBMITTED), eq(user), propertiesCaptor.capture());
        return propertiesCaptor.getValue();
    }

    @Test
    @DisplayName("a first submission records kyc_submitted once, carrying only the banded attempt")
    void recordsOneEventWithTheBandedAttempt() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(encryptionService.encrypt(anyString()))
                .thenAnswer(invocation -> "ENC:" + invocation.getArgument(0));
        when(encryptionService.hashSsn(anyString())).thenReturn("ssn-hash");

        boolean persisted = service.persistSubmission(USER_ID, brokerResult(), request());

        assertThat(persisted).isTrue();
        assertThat(recordedProperties()).isEqualTo(Map.of("attempt", "1"));

        // The state change the event describes, in the same transaction.
        verify(userRepository).save(user);
        assertThat(user.getAccountStatus()).isEqualTo("SUBMITTED");
        assertThat(user.getKycAttempts()).isEqualTo(1);
        assertThat(user.getKycSubmittedAt()).isNotNull();
        assertThat(user.getAlpacaAccountId()).isEqualTo("ENC:alpaca-account-id");
        assertThat(user.getAlpacaAccountNumber()).isEqualTo("ENC:alpaca-account-number");
        assertThat(user.getSsn()).isEqualTo("ENC:000-00-0000");
        assertThat(user.getSsnHash()).isEqualTo("ssn-hash");
        assertThat(user.getFirstName()).isEqualTo("Ada");
        assertThat(user.getLastName()).isEqualTo("Lovelace");
    }

    @Test
    @DisplayName("the second submission bands as 2")
    void secondSubmissionBandsAsTwo() {
        user.setKycAttempts(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.persistSubmission(USER_ID, brokerResult(), request());

        assertThat(recordedProperties()).isEqualTo(Map.of("attempt", "2"));
        assertThat(user.getKycAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("the third submission bands as 3_plus, and so does the twelfth")
    void thirdAndLaterSubmissionsBandAsThreePlus() {
        user.setKycAttempts(2);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.persistSubmission(USER_ID, brokerResult(), request());

        assertThat(recordedProperties()).isEqualTo(Map.of("attempt", "3_plus"));
        assertThat(user.getKycAttempts()).isEqualTo(3);

        // The band is where the exact count stops leaving the server.
        assertThat(KycSubmissionService.attemptBand(12)).isEqualTo("3_plus");
    }

    @Test
    @DisplayName("a null attempt counter on an older row still bands as the first attempt")
    void aNullCounterIsTreatedAsNoAttemptsYet() {
        user.setKycAttempts(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.persistSubmission(USER_ID, brokerResult(), request());

        assertThat(recordedProperties()).isEqualTo(Map.of("attempt", "1"));
        assertThat(user.getKycAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("no user row means no state change and no event")
    void recordsNothingWhenTheUserRowIsGone() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        boolean persisted = service.persistSubmission(USER_ID, brokerResult(), request());

        assertThat(persisted).isFalse();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(analyticsOutboxService);
    }

    @Test
    @DisplayName("an unauthenticated caller is not even looked up, and records nothing")
    void recordsNothingWithoutAnAuthenticatedUser() {
        boolean persisted = service.persistSubmission(null, brokerResult(), request());

        assertThat(persisted).isFalse();
        verifyNoInteractions(userRepository, encryptionService, analyticsOutboxService);
    }
}
