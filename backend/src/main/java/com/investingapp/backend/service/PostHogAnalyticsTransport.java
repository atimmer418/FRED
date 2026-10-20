package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts batches to PostHog's batch endpoint.
 *
 * The project key is the same public ingestion key the app uses; it identifies the project, it is
 * not a secret, and it still comes from the environment so dev and prod can be pointed at
 * different projects without a rebuild. With no key configured this throws, which leaves the rows
 * PENDING rather than marking them sent: a missing key must look like an outage, not like success.
 */
@Component
public class PostHogAnalyticsTransport implements AnalyticsTransport {

    private static final Logger logger = LoggerFactory.getLogger(PostHogAnalyticsTransport.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String host;

    public PostHogAnalyticsTransport(ObjectMapper objectMapper,
                                     @Value("${app.analytics.posthog.key:}") String apiKey,
                                     @Value("${app.analytics.posthog.host:https://us.i.posthog.com}") String host,
                                     @Value("${app.analytics.posthog.timeout-ms:10000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;

        // Timeouts are explicit: a hung ingest endpoint must not hold a scheduler thread open.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public void send(List<Map<String, Object>> events) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.analytics.posthog.key is not configured; refusing to mark events sent");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", apiKey);
        body.put("batch", events);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                host + "/batch/",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("PostHog returned " + response.getStatusCode());
        }
        logger.info("analytics: published {} event(s)", events.size());
    }
}
