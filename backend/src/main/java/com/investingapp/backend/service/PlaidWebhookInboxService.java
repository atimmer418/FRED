package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The Plaid webhook inbox. Every verified webhook becomes one plaid_webhook_events row; nothing is
 * processed here (the processor is Phase 1). dedupe_key = sha256(raw body || ":" || jwt iat): a
 * Plaid retry carries a fresh iat and is a new row, while a body-only key would wrongly drop the
 * byte-identical daily SYNC_UPDATES_AVAILABLE. Rows for an unknown or removed item_id, or for
 * another Plaid environment, are stored IGNORED so the receiver still answers 200.
 */
@Service
public class PlaidWebhookInboxService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookInboxService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PlaidWebhookEventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${plaid.env}")
    private String plaidEnv;

    /**
     * @throws IllegalArgumentException the body is not JSON or lacks webhook_type / webhook_code
     *                                  (the controller answers 503 so Plaid retries)
     */
    @Transactional
    public PlaidWebhookEvent record(byte[] rawBody, long iat) {
        String dedupeKey = dedupeKey(rawBody, iat);
        Optional<PlaidWebhookEvent> existing = eventRepository.findByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("webhook body is not JSON", e);
        }
        String webhookType = field(payload, "webhook_type", true);
        String webhookCode = field(payload, "webhook_code", true);
        String itemId = field(payload, "item_id", false);
        String environment = field(payload, "environment", false);
        Long userId = resolveUserId(itemId);
        boolean sameEnvironment = plaidEnv != null && plaidEnv.equalsIgnoreCase(environment);
        PlaidWebhookEventStatus status = userId != null && sameEnvironment
                ? PlaidWebhookEventStatus.RECEIVED
                : PlaidWebhookEventStatus.IGNORED;

        PlaidWebhookEvent event = new PlaidWebhookEvent();
        event.setDedupeKey(dedupeKey);
        event.setWebhookType(webhookType);
        event.setWebhookCode(webhookCode);
        event.setPlaidItemId(itemId);
        event.setUserId(userId);
        event.setEnvironment(environment == null ? "unknown" : environment);
        event.setPayloadJson(new String(rawBody, StandardCharsets.UTF_8));
        event.setStatus(status);
        event.setAttempts((short) 0);
        event.setJwtIat(iat);
        event.setReceivedAt(EasternTime.now());

        PlaidWebhookEvent saved = eventRepository.save(event);
        logger.info("plaid.webhook.received type={} code={} status={} user_id={}",
                webhookType, webhookCode, status, userId);
        return saved;
    }

    private Long resolveUserId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            User user = userRepository.findByPlaidItemId(itemId);
            return user == null ? null : user.getId();
        } catch (IncorrectResultSizeDataAccessException e) {
            // plaid_item_id has no unique constraint; a sandbox Item reused across users must not drop the row
            logger.error("alert.plaid.webhook.ambiguous_item item_id={}", itemId);
            return null;
        }
    }

    private static String field(JsonNode payload, String name, boolean required) {
        JsonNode node = payload.get(name);
        String value = node == null || node.isNull() ? null : node.asText();
        if (required && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("webhook body is missing " + name);
        }
        return value;
    }

    static String dedupeKey(byte[] rawBody, long iat) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(rawBody);
            digest.update(":".getBytes(StandardCharsets.US_ASCII));
            digest.update(Long.toString(iat).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
