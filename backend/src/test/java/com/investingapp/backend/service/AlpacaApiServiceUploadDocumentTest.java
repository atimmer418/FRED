package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What FRED sends the broker for an identity document, checked against the broker's documentation
 * (Alpaca Broker API, POST /v1/accounts/{account_id}/documents/upload): the body is a LIST of
 * documents, each with document_type, content and mime_type, and success is 204 with no body.
 */
class AlpacaApiServiceUploadDocumentTest {

    private static final String URL = "http://broker/v1/accounts/acct-1/documents/upload";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private AlpacaApiService service;

    @BeforeEach
    void setUp() {
        service = new AlpacaApiService("key", "secret", "http://broker/v1", "http://trade/v2", mock(AlpacaService.class));
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("the document goes to the broker as a list of one, and 204 with no body is success")
    @SuppressWarnings("unchecked")
    void sendsAListAndAcceptsNoContent() throws Exception {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        Map<String, Object> result = service.uploadDocument("acct-1", "identity_verification", "image/jpeg", "/9j/Cg==");

        assertThat(result).containsEntry("success", true).doesNotContainKey("error").doesNotContainKey("raw_response");
        ArgumentCaptor<HttpEntity<String>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.POST), sent.capture(), eq(String.class));
        JsonNode body = new ObjectMapper().readTree(sent.getValue().getBody());
        assertThat(body.isArray()).as("the broker takes a list, not one object").isTrue();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("document_type").asText()).isEqualTo("identity_verification");
        assertThat(body.get(0).path("mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(body.get(0).path("content").asText()).isEqualTo("/9j/Cg==");
    }

    @Test
    @DisplayName("a refusal by the broker comes back as a fixed sentence, with none of the broker's text")
    void brokerRefusalIsAFixedSentence() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable",
                        HttpHeaders.EMPTY, "{\"code\":40010001,\"message\":\"LEAKMARK bad doc\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        Map<String, Object> result = service.uploadDocument("acct-1", "identity_verification", "image/jpeg", "/9j/Cg==");

        assertThat(result).containsKey("error").doesNotContainKey("success");
        assertThat(String.valueOf(result.get("error"))).doesNotContain("LEAKMARK").doesNotContain("422")
                .doesNotContain("acct-1");
    }
}
