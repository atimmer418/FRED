package com.investingapp.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlpacaApiServiceAchRelationshipTest {

    private static final String RELS = "http://broker/v1/accounts/acct-1/ach_relationships";
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private AlpacaApiService service;

    @BeforeEach
    void setUp() {
        service = new AlpacaApiService("key", "secret", "http://broker/v1", "http://trade/v2", mock(AlpacaService.class));
        // restTemplate is private final and built in the constructor; reflection replaces it for the test.
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    private static HttpClientErrorException http(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void conflict409NeverAdoptsOldRelationship() {
        when(restTemplate.exchange(eq(RELS), eq(HttpMethod.POST), any(), eq(String.class))).thenThrow(http(HttpStatus.CONFLICT));

        Map<String, Object> result = service.createAchRelationship("acct-1", "Ada Lovelace", "CHECKING", "000123456789", "021000021", "Checking");

        assertThat(result).containsEntry("conflict", true).containsKey("error").doesNotContainKey("id");
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void deleteTreatsGoneAsSuccessAndOutageAsFailure() {
        when(restTemplate.exchange(eq(RELS + "/rel-1"), eq(HttpMethod.DELETE), any(), eq(String.class))).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));
        when(restTemplate.exchange(eq(RELS + "/rel-2"), eq(HttpMethod.DELETE), any(), eq(String.class))).thenThrow(http(HttpStatus.NOT_FOUND));
        when(restTemplate.exchange(eq(RELS + "/rel-3"), eq(HttpMethod.DELETE), any(), eq(String.class))).thenThrow(new ResourceAccessException("timeout"));

        assertThat(service.deleteAchRelationship("acct-1", "rel-1")).isTrue();
        assertThat(service.deleteAchRelationship("acct-1", "rel-2")).isTrue();
        assertThat(service.deleteAchRelationship("acct-1", "rel-3")).isFalse();
    }

    @Test
    void listFiltersByStatusAndMapsEveryField() {
        String body = "[{\"id\":\"rel-1\",\"status\":\"APPROVED\",\"created_at\":\"2026-10-19T14:00:00Z\",\"bank_account_number\":\"****1234\"},"
                + "{\"id\":\"rel-2\",\"status\":\"CANCEL_REQUESTED\"},"
                + "{\"status\":\"QUEUED\"}]";
        when(restTemplate.exchange(eq(RELS + "?statuses=QUEUED,APPROVED"), eq(HttpMethod.GET), any(), eq(String.class))).thenReturn(ResponseEntity.ok(body));

        assertThat(service.listAchRelationships("acct-1", List.of("QUEUED", "APPROVED"))).containsExactly(
                new AchRelationshipSummary("rel-1", "APPROVED", "2026-10-19T14:00:00Z", "****1234"),
                new AchRelationshipSummary("rel-2", "CANCEL_REQUESTED", null, null));
    }
}
