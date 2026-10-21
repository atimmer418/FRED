package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Deletes a PostHog person, by distinct id.
 *
 * This needs a PERSONAL api key with person:read and person:write, which is a different and far
 * more powerful credential than the public project key the event publisher uses. It is read from
 * the environment and is never written to a properties file. With no key configured every
 * deletion THROWS, which leaves the request PENDING rather than marking it done: an unconfigured
 * key must look like an outage, because quietly marking deletions complete when nothing was
 * deleted is the worst possible failure for this particular job.
 *
 * PostHog has no "delete by distinct id" endpoint, so this is two calls: find the person, then
 * delete them by their internal id. A person who cannot be found is treated as already deleted.
 */
@Component
public class PostHogPersonGateway implements AnalyticsPersonGateway {

    private static final Logger logger = LoggerFactory.getLogger(PostHogPersonGateway.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String personalApiKey;
    private final String host;
    private final String projectId;

    public PostHogPersonGateway(
            ObjectMapper objectMapper,
            @Value("${app.analytics.posthog.personal-api-key:}") String personalApiKey,
            @Value("${app.analytics.posthog.app-host:https://us.posthog.com}") String host,
            @Value("${app.analytics.posthog.project-id:}") String projectId,
            @Value("${app.analytics.posthog.timeout-ms:10000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.personalApiKey = personalApiKey;
        this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.projectId = projectId;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public void deletePerson(String publicId) throws Exception {
        if (personalApiKey == null || personalApiKey.isBlank() || projectId == null || projectId.isBlank()) {
            throw new IllegalStateException(
                    "app.analytics.posthog.personal-api-key and project-id are not configured; "
                            + "refusing to mark a deletion complete that was never sent");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(personalApiKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> found = restTemplate.exchange(
                host + "/api/projects/" + projectId + "/persons/?distinct_id="
                        + java.net.URLEncoder.encode(publicId, java.nio.charset.StandardCharsets.UTF_8),
                HttpMethod.GET, request, String.class);

        JsonNode results = objectMapper.readTree(found.getBody()).path("results");
        if (!results.isArray() || results.isEmpty()) {
            // Already gone, or never reached the vendor at all. Either way the obligation is met,
            // and retrying forever over it would bury the requests that still matter.
            logger.info("analytics: no PostHog person for this id; treating the deletion as done");
            return;
        }

        String personId = results.get(0).path("id").asText();
        // delete_events removes the events as well as the person record, which is what "delete my
        // account" means to the person who asked.
        restTemplate.exchange(
                host + "/api/projects/" + projectId + "/persons/" + personId + "/?delete_events=true",
                HttpMethod.DELETE, request, String.class);

        logger.info("analytics: deleted a PostHog person record and its events");
    }
}
