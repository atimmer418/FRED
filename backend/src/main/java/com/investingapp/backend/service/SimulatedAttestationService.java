package com.investingapp.backend.service;

import com.investingapp.backend.dto.RegistrationFinishResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserProgress;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.security.jwt.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

/**
 * Simulated passkey registration, used to sign in on simulators and in automated tests where no
 * platform authenticator exists.
 *
 * <p>This bean is registered only in the local/dev/test/ci profiles. Where it is absent,
 * {@code WebAuthnController} rejects the SIMULATED_ATTESTATION credential outright, so no profile
 * other than these can register a passkey-less account. {@link
 * com.investingapp.backend.config.DevToolsProdGuard} additionally refuses to start the application
 * if this bean is ever registered under the prod profile.
 */
@Service
@Profile({"local", "dev", "test", "ci"})
public class SimulatedAttestationService {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedAttestationService.class);

    private final UserRepository userRepository;
    private final UserDetailsService userDetailsService;
    private final JwtUtils jwtUtils;
    private final DevToolsKeyValidator keyValidator;

    public SimulatedAttestationService(UserRepository userRepository,
            UserDetailsService userDetailsService,
            JwtUtils jwtUtils,
            DevToolsKeyValidator keyValidator) {
        this.userRepository = userRepository;
        this.userDetailsService = userDetailsService;
        this.jwtUtils = jwtUtils;
        this.keyValidator = keyValidator;
    }

    /**
     * Creates the user if needed and issues a JWT, with no WebAuthn ceremony. Account creation is
     * unchanged from the branch that previously lived in WebAuthnController.
     *
     * @param providedDevToolsKey the caller's {@code X-Dev-Tools-Key} header
     * @return the registration response, or null when the dev-tools key is missing or wrong
     */
    public RegistrationFinishResponse register(String email, String providedDevToolsKey) {
        if (!keyValidator.matches(providedDevToolsKey)) {
            logger.warn("Rejected SIMULATED passkey registration for {}: missing or wrong {}",
                    email, DevToolsKeyValidator.HEADER);
            return null;
        }

        logger.warn("SIMULATED passkey registration used for email: {}", email);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = new User(email);

            UserProgress userProgress = new UserProgress();
            user.setUserProgress(userProgress);

            userRepository.save(user);
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String nextStep = (user.getUserProgress() != null) ? user.getUserProgress().getNextStep() : "get-started";
        String jwt = jwtUtils.generateJwtToken(authentication, nextStep);

        return new RegistrationFinishResponse(
                true,
                "Simulated registration success.",
                jwt,
                user.getId(),
                user.getEmail(),
                user.getPublicId());
    }
}
