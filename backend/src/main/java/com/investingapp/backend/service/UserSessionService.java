package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserSession;
import com.investingapp.backend.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Starts the session behind every sign-in.
 *
 * <p>Every token FRED issues belongs to a session row, so the account can always take it back:
 * revoking the session, or recovering the account, ends the token. A sign-in that cannot get a
 * session fails; it never falls back to a token with none.
 *
 * <p>Each sign-in gets a new row. An earlier session on the same device is removed, not reused. If
 * the row were reused, a token revoked yesterday would come back to life the next time its owner
 * signed in on that device. Sessions that were already ended, or have run out, are removed at the same time.
 */
@Service
public class UserSessionService {

    private static final Logger logger = LoggerFactory.getLogger(UserSessionService.class);

    private static final int DEVICE_ID_MAX = 64;
    private static final int DEVICE_INFO_MAX = 512;
    private static final int IP_MAX = 45;

    private final UserSessionRepository sessions;
    /** How long one sign-in lasts (see JwtUtils). Sessions older than this are over. */
    private final long sessionMaxMs;

    public UserSessionService(UserSessionRepository sessions,
            @Value("${jwt.session.max.ms:43200000}") long sessionMaxMs) {
        this.sessions = sessions;
        this.sessionMaxMs = sessionMaxMs;
    }

    /**
     * Starts a session for a user who has just proved who they are. Device details come from the
     * request being handled, when there is one.
     *
     * @return the saved session, always with an id
     */
    @Transactional
    public UserSession start(User user) {
        HttpServletRequest request = currentRequest();
        String deviceId = clean(header(request, "X-Device-ID"), DEVICE_ID_MAX);
        String deviceName = header(request, "X-Device-Name");
        String userAgent = header(request, "User-Agent");

        // Out with what this sign-in replaces: the device's earlier session, sessions already
        // ended, and sessions whose 12 hours ran out. Their tokens are refused either way;
        // removing the rows keeps the table, and the user's list of signed-in devices, honest.
        // "Started long ago" has two hours of slack on top of the session lifetime, so a clock
        // change on the server can never remove a session that is still running.
        LocalDateTime startedBefore = LocalDateTime.now().minus(Duration.ofMillis(sessionMaxMs)).minusHours(2);
        // Only when the user has sessions at all. A delete that matches nothing still locks the
        // gap in the index, and two brand new users finishing sign-up at once would then block
        // each other's insert and one would be rolled back.
        int removed = sessions.existsByUserId(user.getId())
                ? sessions.deleteReplacedEndedAndExpired(user.getId(), deviceId, startedBefore) : 0;
        if (removed > 0) {
            logger.info("Removed {} replaced or ended session(s) for user {}", removed, user.getId());
        }

        UserSession session = new UserSession();
        session.setUser(user);
        session.setDeviceId(deviceId);
        String info = (deviceName != null && !deviceName.isBlank()) ? deviceName
                : (userAgent != null && !userAgent.isBlank()) ? userAgent : "Unknown Device";
        session.setDeviceInfo(clean(info, DEVICE_INFO_MAX));
        session.setIpAddress(request == null ? null : clean(request.getRemoteAddr(), IP_MAX));
        session.setActive(true);
        session.setLastActive(LocalDateTime.now());

        UserSession saved = sessions.saveAndFlush(session);
        logger.info("Started session {} for user {}", saved.getId(), user.getId());
        return saved;
    }

    /** Ends one session, if it exists. Its token is refused from now on. */
    @Transactional
    public void end(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.findById(sessionId).ifPresent(session -> {
            session.setActive(false);
            sessions.save(session);
        });
    }

    private static HttpServletRequest currentRequest() {
        return (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                ? attributes.getRequest() : null;
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    /** Trimmed and cut to the column width; null when blank. An over-long value never fails a sign-in. */
    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
