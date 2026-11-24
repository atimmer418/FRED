package com.investingapp.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investingapp.backend.model.AdminAction;
import com.investingapp.backend.repository.AdminActionRepository;
import com.investingapp.backend.security.AdminKeyFilter;
import com.investingapp.backend.util.EasternTime;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * admin_actions (spec 12.2). record: refusals and GETs, REQUIRES_NEW via a template (so a failed
 * commit is caught too), never throws. recordInTransaction: mutating verbs, MANDATORY, commits or
 * rolls back with the state change. JSON keys containing token, cursor, descriptor, secret or
 * password, or starting with ssn, are dropped at every depth (a backstop).
 */
@Service
public class AdminAuditService {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuditService.class);

    static final int ACTOR_MAX = 80;
    static final int VERB_MAX = 64;
    static final int TARGET_MAX = 80;
    static final int REASON_MAX = 255;

    static final List<String> FORBIDDEN_KEY_FRAGMENTS = List.of("token", "cursor", "descriptor", "secret", "password");

    private final AdminActionRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AdminAuditService(AdminActionRepository repository, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String actor, Long userId, String verb, String targetId,
            Object before, Object after, String reason) {
        try {
            requiresNew.executeWithoutResult(status ->
                    repository.save(build(actor, userId, verb, targetId, before, after, reason)));
            logger.info("admin.action actor={} verb={} user={}", cut(actor, ACTOR_MAX), cut(verb, VERB_MAX), userId);
        } catch (RuntimeException e) {
            logger.error("admin.action audit write failed verb={} user={} cause={}",
                    cut(verb, VERB_MAX), userId, e.getClass().getSimpleName());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(String actor, Long userId, String verb, String targetId,
            Object before, Object after, String reason) {
        repository.save(build(actor, userId, verb, targetId, before, after, reason));
        logger.info("admin.action actor={} verb={} user={}", cut(actor, ACTOR_MAX), cut(verb, VERB_MAX), userId);
    }

    /** The actor AdminKeyFilter authenticated; throws when the filter did not run, so a verb fails closed. */
    public String actor(HttpServletRequest request) {
        Object actor = request.getAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE);
        if (actor instanceof String handle && !handle.isBlank()) {
            return handle;
        }
        throw new IllegalStateException(AdminKeyFilter.ACTOR_ATTRIBUTE
                + " is missing: AdminKeyFilter did not run for " + request.getRequestURI());
    }

    private AdminAction build(String actor, Long userId, String verb, String targetId,
            Object before, Object after, String reason) {
        AdminAction row = new AdminAction();
        row.setActor(cut(actor, ACTOR_MAX));
        row.setUserId(userId);
        row.setVerb(cut(verb, VERB_MAX));
        row.setTargetId(cut(targetId, TARGET_MAX));
        row.setBeforeJson(toJson(before));
        row.setAfterJson(toJson(after));
        row.setReason(cut(reason, REASON_MAX));
        row.setCreatedAt(EasternTime.now());
        return row;
    }

    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            scrub(tree);
            return objectMapper.writeValueAsString(tree);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return "{\"unserializable\":\"" + value.getClass().getSimpleName() + "\"}";
        }
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isForbidden(name)) {
                    object.remove(name);
                } else {
                    scrub(object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AdminAuditService::scrub);
        }
    }

    static boolean isForbidden(String propertyName) {
        String lower = propertyName.toLowerCase(Locale.ROOT);
        return lower.startsWith("ssn") || FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    static String cut(String value, int width) {
        return value == null || value.length() <= width ? value : value.substring(0, width);
    }
}
