package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 2.2 and 2.3: one endpoint mints both kinds of Link token. LINK carries products plus the
 * Transactions consent; UPDATE carries the stored access token, no products, and the consent only
 * while the tri-state flag is not already true. Every token carries redirect_uri, webhook and the
 * customization name. PlaidService is field-injected, so collaborators go in by reflection as in
 * PlaidLinkedAnalyticsTest.
 */
class PlaidServiceLinkTokenTest {

    private static final String WEBHOOK_URL = "https://api-test.fredvested.com/api/plaid/webhook";
    private static final String REDIRECT_URI = "https://fredvested.com/plaid/oauth";
    private static final String CUSTOMIZATION = "paycheck_detection";
    private static final String ENC_TOKEN = "ENC:encrypted-access-token";
    private static final String RAW_TOKEN = "access-sandbox-raw-token";

    private PlaidService plaidService;
    private PlaidApi plaidApi;

    @BeforeEach
    void setUp() {
        plaidService = new PlaidService();
        plaidApi = mock(PlaidApi.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        ReflectionTestUtils.setField(plaidService, "plaidApi", plaidApi);
        ReflectionTestUtils.setField(plaidService, "userRepository", mock(UserRepository.class));
        ReflectionTestUtils.setField(plaidService, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(plaidService, "plaidClientName", "FRED (Test)");
        ReflectionTestUtils.setField(plaidService, "plaidWebhookUrl", WEBHOOK_URL);
        ReflectionTestUtils.setField(plaidService, "plaidRedirectUri", REDIRECT_URI);
        ReflectionTestUtils.setField(plaidService, "plaidLinkCustomization", CUSTOMIZATION);
        when(encryptionService.decrypt(ENC_TOKEN)).thenReturn(RAW_TOKEN);
    }

    private User user() {
        User user = new User("link-token@fredvested.com");
        user.setId(99L);
        return user;
    }

    private User linkedUser(Boolean consented) {
        User user = user();
        user.setPlaidAccessToken(ENC_TOKEN);
        user.setPlaidTransactionsConsented(consented);
        return user;
    }

    @SuppressWarnings("unchecked")
    private void plaidAnswers(Response<LinkTokenCreateResponse> answer) {
        Call<LinkTokenCreateResponse> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(answer);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(plaidApi.linkTokenCreate(any(LinkTokenCreateRequest.class))).thenReturn(call);
    }

    private LinkTokenCreateRequest requestFor(User user, PlaidService.LinkMode mode) throws IOException {
        plaidAnswers(Response.success(new LinkTokenCreateResponse().linkToken("link-sandbox-abc")));
        plaidService.createLinkToken(user, mode);
        ArgumentCaptor<LinkTokenCreateRequest> captor = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidApi).linkTokenCreate(captor.capture());
        return captor.getValue();
    }

    @Test
    void linkModeRequest() throws IOException {
        LinkTokenCreateRequest sent = requestFor(user(), PlaidService.LinkMode.LINK);

        assertThat(sent.getUser().getClientUserId()).isEqualTo("99");
        assertThat(sent.getProducts()).containsExactly(Products.AUTH, Products.IDENTITY);
        assertThat(sent.getAdditionalConsentedProducts()).containsExactly(Products.TRANSACTIONS);
        assertThat(sent.getWebhook()).isEqualTo(WEBHOOK_URL);
        assertThat(sent.getRedirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(sent.getLinkCustomizationName()).isEqualTo(CUSTOMIZATION);
        assertThat(sent.getAccessToken()).isNull();
    }

    @Test
    void updateModeConsentUnknown() throws IOException {
        LinkTokenCreateRequest sent = requestFor(linkedUser(null), PlaidService.LinkMode.UPDATE);

        assertThat(sent.getAccessToken()).isEqualTo(RAW_TOKEN);
        assertThat(sent.getProducts()).isNull();
        assertThat(sent.getAdditionalConsentedProducts()).containsExactly(Products.TRANSACTIONS);
        assertThat(sent.getWebhook()).isEqualTo(WEBHOOK_URL);
        assertThat(sent.getRedirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(sent.getLinkCustomizationName()).isEqualTo(CUSTOMIZATION);
    }

    @Test
    void updateModeAlreadyConsented() throws IOException {
        LinkTokenCreateRequest sent = requestFor(linkedUser(true), PlaidService.LinkMode.UPDATE);

        assertThat(sent.getAccessToken()).isEqualTo(RAW_TOKEN);
        assertThat(sent.getProducts()).isNull();
        assertThat(sent.getAdditionalConsentedProducts()).isNull();
    }

    @Test
    void updateModeWithoutAnItemIsUnavailable() {
        assertThatThrownBy(() -> plaidService.createLinkToken(user(), PlaidService.LinkMode.UPDATE))
                .isInstanceOf(UpdateModeUnavailableException.class);
        verify(plaidApi, never()).linkTokenCreate(any(LinkTokenCreateRequest.class));
    }

    /** A fresh error body per call: okhttp drains a ResponseBody on the first read. */
    private void plaidRefuses() {
        plaidAnswers(Response.error(400, ResponseBody.create(
                "{\"error_code\":\"INVALID_ACCESS_TOKEN\"}", MediaType.parse("application/json"))));
    }

    @Test
    void refusalMapsByMode() {
        plaidRefuses();
        assertThatThrownBy(() -> plaidService.createLinkToken(linkedUser(false), PlaidService.LinkMode.UPDATE))
                .isInstanceOf(UpdateModeUnavailableException.class);
        plaidRefuses();
        assertThatThrownBy(() -> plaidService.createLinkToken(user(), PlaidService.LinkMode.LINK))
                .isInstanceOf(IOException.class);
    }

    @Test
    void modeParsing() {
        assertThat(PlaidService.LinkMode.fromRequest(null)).isEqualTo(PlaidService.LinkMode.LINK);
        assertThat(PlaidService.LinkMode.fromRequest("  ")).isEqualTo(PlaidService.LinkMode.LINK);
        assertThat(PlaidService.LinkMode.fromRequest("link")).isEqualTo(PlaidService.LinkMode.LINK);
        assertThat(PlaidService.LinkMode.fromRequest("Update")).isEqualTo(PlaidService.LinkMode.UPDATE);
        assertThatThrownBy(() -> PlaidService.LinkMode.fromRequest("refresh"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
