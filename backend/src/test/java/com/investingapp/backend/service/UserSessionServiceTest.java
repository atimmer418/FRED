package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserSession;
import com.investingapp.backend.repository.UserSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every sign-in gets a new session row; an older one on the same device is ended, never reused. */
class UserSessionServiceTest {

    private UserSessionRepository sessions;
    private UserSessionService service;
    private User user;

    @BeforeEach
    void setUp() {
        sessions = mock(UserSessionRepository.class);
        service = new UserSessionService(sessions, 43_200_000L);
        user = new User("user@fredvested.com");
        user.setId(7L);
        when(sessions.existsByUserId(7L)).thenReturn(true);
        when(sessions.saveAndFlush(any(UserSession.class))).thenAnswer(invocation -> {
            UserSession saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private UserSession saved() {
        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(sessions).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a sign-in removes the earlier session on that device and saves a new active one")
    void newRowAndOldOneEnded() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Device-ID", "device-1");
        request.addHeader("X-Device-Name", "Andy's iPhone");

        UserSession session = service.start(user);

        ArgumentCaptor<java.time.LocalDateTime> startedBefore = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(sessions).deleteReplacedEndedAndExpired(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("device-1"), startedBefore.capture());
        // 12 hours of session plus 2 hours of slack.
        assertThat(startedBefore.getValue()).isCloseTo(java.time.LocalDateTime.now().minusHours(14),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(session.getId()).isEqualTo(99L);
        UserSession row = saved();
        assertThat(row.getId()).isEqualTo(99L);
        assertThat(row.isActive()).isTrue();
        assertThat(row.getUser()).isSameAs(user);
        assertThat(row.getDeviceId()).isEqualTo("device-1");
        assertThat(row.getDeviceInfo()).isEqualTo("Andy's iPhone");
        assertThat(row.getIpAddress()).isEqualTo("203.0.113.9");
        assertThat(row.getLastActive()).isNotNull();
    }

    @Test
    @DisplayName("a user with no sessions yet (a new sign-up) runs no delete at all")
    void newUserRunsNoDelete() {
        request().addHeader("X-Device-ID", "device-1");
        when(sessions.existsByUserId(7L)).thenReturn(false);

        service.start(user);

        verify(sessions, org.mockito.Mockito.never()).deleteReplacedEndedAndExpired(any(), any(), any());
        assertThat(saved().isActive()).isTrue();
    }

    @Test
    @DisplayName("ending a session marks it inactive; an unknown or missing id is ignored")
    void endSession() {
        UserSession live = new UserSession();
        live.setId(5L);
        live.setActive(true);
        when(sessions.findById(5L)).thenReturn(java.util.Optional.of(live));
        when(sessions.findById(6L)).thenReturn(java.util.Optional.empty());

        service.end(5L);
        service.end(6L);
        service.end(null);

        assertThat(live.isActive()).isFalse();
        verify(sessions).save(live);
    }

    @Test
    @DisplayName("with no device id nothing else is ended, and the browser name is the device")
    void noDeviceId() {
        MockHttpServletRequest request = request();
        request.addHeader("User-Agent", "Mozilla/5.0 test");

        service.start(user);

        verify(sessions).deleteReplacedEndedAndExpired(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.isNull(), any(java.time.LocalDateTime.class));
        assertThat(saved().getDeviceId()).isNull();
        assertThat(saved().getDeviceInfo()).isEqualTo("Mozilla/5.0 test");
    }

    @Test
    @DisplayName("over-long headers are cut to fit, so they can never fail a sign-in")
    void longHeadersAreCut() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Device-ID", "d".repeat(200));
        request.addHeader("User-Agent", "u".repeat(2000));

        service.start(user);

        assertThat(saved().getDeviceId()).hasSize(64);
        assertThat(saved().getDeviceInfo()).hasSize(512);
        verify(sessions).deleteReplacedEndedAndExpired(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("d".repeat(64)), any(java.time.LocalDateTime.class));
    }

    @Test
    @DisplayName("it still works with no request at all")
    void noRequest() {
        service.start(user);

        assertThat(saved().getDeviceInfo()).isEqualTo("Unknown Device");
        assertThat(saved().getIpAddress()).isNull();
    }

    @Test
    @DisplayName("when the session cannot be saved the sign-in fails; there is no fallback")
    void failureIsNotSwallowed() {
        request();
        when(sessions.saveAndFlush(any(UserSession.class))).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.start(user)).isInstanceOf(IllegalStateException.class);
    }
}
