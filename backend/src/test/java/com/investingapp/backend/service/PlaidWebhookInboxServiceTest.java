package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every verified webhook becomes one inbox row. The dedupe key folds in the JWT iat: a Plaid retry
 * has a fresh iat and is a new row (handlers are idempotent), while a body-only key would wrongly
 * drop the byte-identical daily SYNC_UPDATES_AVAILABLE. Rows for an unknown Item or another Plaid
 * environment are stored IGNORED so the receiver still answers 200.
 */
class PlaidWebhookInboxServiceTest {

    private static final String ITEM_ID = "item-id-abcdef123456";
    private static final byte[] SYNC_BODY = body("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", ITEM_ID, "sandbox");

    private PlaidWebhookInboxService service;
    private PlaidWebhookEventRepository eventRepository;
    private UserRepository userRepository;

    static byte[] body(String type, String code, String itemId, String env) {
        return ("{\"webhook_type\":\"" + type + "\",\"webhook_code\":\"" + code + "\",\"item_id\":\"" + itemId
                + "\",\"environment\":\"" + env + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** sha256(raw body || ":" || iat) as lowercase hex, computed on the bytes exactly as the spec says. */
    static String expectedKey(byte[] body, long iat) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(body);
        digest.update(":".getBytes(StandardCharsets.US_ASCII));
        digest.update(Long.toString(iat).getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    @BeforeEach
    void setUp() {
        service = new PlaidWebhookInboxService();
        eventRepository = mock(PlaidWebhookEventRepository.class);
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "plaidEnv", "sandbox");

        when(eventRepository.findByDedupeKey(anyString())).thenReturn(Optional.empty());
        when(eventRepository.save(any(PlaidWebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        User user = new User("inbox@fredvested.com");
        user.setId(99L);
        when(userRepository.findByPlaidItemId(ITEM_ID)).thenReturn(user);
    }

    @Test
    void knownItemIsStoredReceived() throws Exception {
        PlaidWebhookEvent event = service.record(SYNC_BODY, 1_700_000_000L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
        assertThat(event.getDedupeKey()).isEqualTo(expectedKey(SYNC_BODY, 1_700_000_000L));
        assertThat(event.getWebhookType()).isEqualTo("TRANSACTIONS");
        assertThat(event.getWebhookCode()).isEqualTo("SYNC_UPDATES_AVAILABLE");
        assertThat(event.getPlaidItemId()).isEqualTo(ITEM_ID);
        assertThat(event.getUserId()).isEqualTo(99L);
        assertThat(event.getEnvironment()).isEqualTo("sandbox");
        assertThat(event.getPayloadJson()).isEqualTo(new String(SYNC_BODY, StandardCharsets.UTF_8));
        assertThat(event.getAttempts()).isEqualTo((short) 0);
        assertThat(event.getJwtIat()).isEqualTo(1_700_000_000L);
        assertThat(event.getReceivedAt()).isNotNull();
        verify(eventRepository).save(event);
    }

    @Test
    void identicalDailySyncBodiesAreNotDeduped() throws Exception {
        PlaidWebhookEvent first = service.record(SYNC_BODY, 1_700_000_000L);
        PlaidWebhookEvent second = service.record(SYNC_BODY, 1_700_086_400L);

        assertThat(first.getDedupeKey()).isNotEqualTo(second.getDedupeKey());
        verify(eventRepository, times(2)).save(any(PlaidWebhookEvent.class));
    }

    @Test
    void sameBodyAndIatReturnsTheExistingRow() throws Exception {
        PlaidWebhookEvent existing = new PlaidWebhookEvent();
        when(eventRepository.findByDedupeKey(expectedKey(SYNC_BODY, 1_700_000_000L))).thenReturn(Optional.of(existing));

        assertThat(service.record(SYNC_BODY, 1_700_000_000L)).isSameAs(existing);
        verify(eventRepository, times(0)).save(any(PlaidWebhookEvent.class));
    }

    @Test
    void unknownItemIsIgnored() {
        PlaidWebhookEvent event = service.record(body("ITEM", "ERROR", "item-unknown", "sandbox"), 1L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isNull();
        assertThat(event.getPlaidItemId()).isEqualTo("item-unknown");
    }

    @Test
    void otherEnvironmentIsIgnored() {
        PlaidWebhookEvent event = service.record(body("ITEM", "ERROR", ITEM_ID, "production"), 1L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isEqualTo(99L);
    }

    @Test
    void ambiguousItemIsIgnoredNotDropped() {
        when(userRepository.findByPlaidItemId(ITEM_ID)).thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        PlaidWebhookEvent event = service.record(SYNC_BODY, 1L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isNull();
        verify(eventRepository).save(event);
    }

    @Test
    void mappingFailureThrowsIllegalArgument() {
        byte[] noCode = "{\"webhook_type\":\"ITEM\",\"item_id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.record(noCode, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(notJson, 1L)).isInstanceOf(IllegalArgumentException.class);
        verify(eventRepository, times(0)).save(any(PlaidWebhookEvent.class));
    }
}
