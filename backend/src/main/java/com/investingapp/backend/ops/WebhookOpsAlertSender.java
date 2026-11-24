package com.investingapp.backend.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.util.EasternTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POSTs one JSON document per alert to app.ops.alert.webhook-url. "text" (Slack) and "content"
 * (Discord) repeat one summary line, since each receiver rejects a body without its own field. The
 * URL embeds the receiver's token: never logged, never in an exception message.
 */
@Component
@ConditionalOnProperty(name = "app.ops.alert.provider", havingValue = "webhook")
public class WebhookOpsAlertSender implements OpsAlertSender {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String webhookUrl;
    private final String env;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WebhookOpsAlertSender(@Value("${app.ops.alert.webhook-url:}") String webhookUrl, Environment environment) {
        this(webhookUrl, String.join(",", environment.getActiveProfiles()),
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    WebhookOpsAlertSender(String webhookUrl, String env, HttpClient client) {
        this.webhookUrl = webhookUrl;
        this.env = env == null || env.isBlank() ? "default" : env;
        this.client = client;
    }

    @Override
    public String send(OpsAlertMessage alert) throws Exception {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("app.ops.alert.webhook-url is not set");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl.trim()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload(alert)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("ops alert receiver answered HTTP " + response.statusCode());
        }
        return OpsAlertService.DELIVERY_SENT;
    }

    Map<String, Object> payload(OpsAlertMessage alert) {
        String summary = "[" + env + "] " + alert.type() + " " + alert.subjectId() + ": " + alert.message();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", alert.type());
        payload.put("subject", alert.subjectId());
        payload.put("message", alert.message());
        payload.put("context", alert.context());
        payload.put("env", env);
        payload.put("at", alert.raisedAt().atZone(EasternTime.ZONE).toOffsetDateTime().toString());
        payload.put("text", summary);
        payload.put("content", summary);
        return payload;
    }
}
