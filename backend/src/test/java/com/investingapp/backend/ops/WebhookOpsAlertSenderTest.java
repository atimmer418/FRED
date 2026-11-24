package com.investingapp.backend.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookOpsAlertSenderTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private volatile int answer = 204;

    @BeforeEach
    void startReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook/secret-token", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/secret-token";
    }

    private static OpsAlertMessage alert() {
        return new OpsAlertMessage(OpsAlerts.ACH_CANCEL_STUCK, "7", "cancel stuck over 24 hours",
                Map.of("user", "7"), LocalDateTime.of(2026, 11, 4, 9, 7));
    }

    @Test
    void postsOneJsonDocumentWithEveryField() throws Exception {
        WebhookOpsAlertSender sender = new WebhookOpsAlertSender(url(), "dev", HttpClient.newHttpClient());

        assertThat(sender.send(alert())).isEqualTo(OpsAlertService.DELIVERY_SENT);

        assertThat(contentType.get()).startsWith("application/json");
        JsonNode json = new ObjectMapper().readTree(body.get());
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("type", "subject", "message", "context", "env", "at", "text", "content");
        assertThat(json.get("type").asText()).isEqualTo("ach.cancel_stuck");
        assertThat(json.get("subject").asText()).isEqualTo("7");
        assertThat(json.get("message").asText()).isEqualTo("cancel stuck over 24 hours");
        assertThat(json.get("context").get("user").asText()).isEqualTo("7");
        assertThat(json.get("env").asText()).isEqualTo("dev");
        assertThat(json.get("at").asText()).isEqualTo("2026-11-04T09:07-05:00");
        assertThat(json.get("text").asText()).isEqualTo("[dev] ach.cancel_stuck 7: cancel stuck over 24 hours");
        assertThat(json.get("content").asText()).isEqualTo(json.get("text").asText());
    }

    @Test
    void non2xxIsAFailureAndNeverEchoesTheUrl() {
        answer = 500;
        WebhookOpsAlertSender sender = new WebhookOpsAlertSender(url(), "dev", HttpClient.newHttpClient());

        assertThatThrownBy(() -> sender.send(alert()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void blankUrlFailsAndTheLoggingSenderReportsLogged() {
        WebhookOpsAlertSender sender = new WebhookOpsAlertSender(" ", "dev", HttpClient.newHttpClient());

        assertThatThrownBy(() -> sender.send(alert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ops.alert.webhook-url is not set");
        assertThat(WebhookOpsAlertSender.TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(new LoggingOpsAlertSender().send(alert())).isEqualTo(OpsAlertService.DELIVERY_LOGGED);
    }
}
