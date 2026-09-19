package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import com.investingapp.backend.dto.RegistrationFinishRequest;
import com.investingapp.backend.dto.RegistrationFinishResponse;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.service.SimulatedAttestationService;
import com.investingapp.backend.service.WebAuthnService;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SIMULATED_ATTESTATION lets a caller register an account with no passkey ceremony. Outside the dev
 * profiles the service bean does not exist, and the controller must then refuse the credential
 * rather than fall through to any account-creating path.
 */
class SimulatedAttestationGatingTest {

    private static final String KEY = "dev-tools-key";
    private static final String EMAIL = "simulated@fredvested.com";

    private ObjectMapper objectMapper;
    private WebAuthnService webAuthnService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webAuthnService = mock(WebAuthnService.class);
    }

    private WebAuthnController controllerWith(SimulatedAttestationService simulated) {
        Cache<String, PublicKeyCredentialCreationOptions> challengeCache = Caffeine.newBuilder().build();
        Cache<String, PublicKeyCredentialRequestOptions> authChallengeCache = Caffeine.newBuilder().build();

        WebAuthnController controller =
                new WebAuthnController(webAuthnService, challengeCache, authChallengeCache, objectMapper);
        ReflectionTestUtils.setField(controller, "simulatedAttestationService", simulated);
        return controller;
    }

    private RegistrationFinishRequest simulatedRequest() {
        RegistrationFinishRequest request = new RegistrationFinishRequest();
        request.setEmail(EMAIL);
        request.setCredential(objectMapper.createObjectNode()
                .set("response", objectMapper.createObjectNode()
                        .put("attestationObject", "SIMULATED_ATTESTATION")));
        return request;
    }

    @Test
    @DisplayName("prod (bean absent): the simulated credential is rejected and no account is created")
    void rejectedWhenServiceBeanAbsent() {
        WebAuthnController controller = controllerWith(null);

        ResponseEntity<RegistrationFinishResponse> response =
                controller.finishRegistration(simulatedRequest(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getJwtToken()).isNull();
        verifyNoInteractions(webAuthnService);
    }

    @Test
    @DisplayName("dev without the dev-tools key: rejected, no token issued")
    void rejectedWhenKeyMissing() {
        SimulatedAttestationService simulated = mock(SimulatedAttestationService.class);
        when(simulated.register(eq(EMAIL), any())).thenReturn(null);

        ResponseEntity<RegistrationFinishResponse> response = controllerWith(simulated)
                .finishRegistration(simulatedRequest(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getJwtToken()).isNull();
    }

    @Test
    @DisplayName("dev with the dev-tools key: the simulated registration succeeds")
    void allowedInDevWithKey() {
        SimulatedAttestationService simulated = mock(SimulatedAttestationService.class);
        when(simulated.register(EMAIL, KEY)).thenReturn(
                new RegistrationFinishResponse(true, "Simulated registration success.", "jwt-token", 7L, EMAIL));

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader(DevToolsKeyValidator.HEADER, KEY);

        ResponseEntity<RegistrationFinishResponse> response =
                controllerWith(simulated).finishRegistration(simulatedRequest(), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getJwtToken()).isEqualTo("jwt-token");
    }

    @Test
    @DisplayName("the service itself refuses to register when the key is wrong")
    void serviceRequiresKey() {
        SimulatedAttestationService service = new SimulatedAttestationService(
                mock(com.investingapp.backend.repository.UserRepository.class),
                mock(org.springframework.security.core.userdetails.UserDetailsService.class),
                mock(com.investingapp.backend.security.jwt.JwtUtils.class),
                new DevToolsKeyValidator(KEY));

        assertThat(service.register(EMAIL, null)).isNull();
        assertThat(service.register(EMAIL, "wrong")).isNull();
    }
}
