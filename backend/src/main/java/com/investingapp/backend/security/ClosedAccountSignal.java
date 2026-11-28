package com.investingapp.backend.security;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Tells "this account is closed" apart from "this session is over".
 *
 * <p>Both are answered 401. A device whose session is over asks for the passkey and carries on. A
 * device whose account is closed must not: there is nothing to sign in to, so asking would trap it
 * on a lock screen it can never pass. The 401 for a closed account therefore carries
 * {@code "code": "ACCOUNT_CLOSED"}, and the app shows a clear screen instead.
 *
 * <p>The account is known to be closed when a token WE signed names, in its pid claim, an account
 * that no longer exists, or one marked closed and waiting to be deleted. The pid is used and not
 * the email because an email can change while the account lives on; the pid never does. A token
 * with no pid proves nothing and gets the plain 401. Because the token must carry our signature,
 * nobody can use this to ask whether an account exists.
 *
 * <p>Only looked at for a token that is already being refused, so it costs an honoured request
 * nothing.
 */
@Component
public class ClosedAccountSignal {

    /** The code the app looks for in a 401 body. */
    public static final String CODE = "ACCOUNT_CLOSED";

    private static final String ATTRIBUTE = ClosedAccountSignal.class.getName() + ".closed";

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    public ClosedAccountSignal(JwtUtils jwtUtils, UserRepository userRepository) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
    }

    /**
     * Call for a correctly signed token that is not being honoured. Marks the request when the
     * token's account is closed. Never throws: on any doubt the request stays unmarked.
     */
    public void noteIfClosed(HttpServletRequest request, String signedToken) {
        try {
            String publicId = jwtUtils.getPublicIdFromJwtToken(signedToken);
            if (publicId == null || publicId.isBlank()) {
                return;
            }
            Optional<User> user = userRepository.findByPublicId(publicId);
            if (user.isEmpty() || user.get().isClosed()) {
                request.setAttribute(ATTRIBUTE, Boolean.TRUE);
            }
        } catch (RuntimeException e) {
            // A lookup failure is not evidence of a closed account.
        }
    }

    /** True when this request carried the token of a closed account. */
    public static boolean isMarked(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
    }
}
