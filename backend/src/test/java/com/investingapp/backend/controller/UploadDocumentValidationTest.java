package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.EncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The identity-document upload used to pass anything on to the broker: any file type, any document
 * type, any size. It now accepts only what the broker's own documentation accepts (Alpaca Broker
 * API, POST /v1/accounts/{account_id}/documents/upload): a PDF, PNG or JPEG, one of the document
 * types the app offers, at most 10 MB once encoded, and a file that really is what it says it is.
 */
class UploadDocumentValidationTest {

    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F' };
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0 };
    private static final byte[] PDF = "%PDF-1.7\n%test".getBytes();

    private final ObjectMapper json = new ObjectMapper();
    private AlpacaApiService alpacaApiService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        alpacaApiService = mock(AlpacaApiService.class);
        UserRepository userRepository = mock(UserRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.decrypt("ENC:acct")).thenReturn("acct-1");
        User user = new User();
        user.setId(1L);
        user.setAccountStatus("ACTION_REQUIRED");
        user.setAlpacaAccountId("ENC:acct");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        when(alpacaApiService.uploadDocument(anyString(), anyString(), anyString(), anyString())).thenReturn(ok);

        mvc = MockMvcBuilders.standaloneSetup(new AlpacaController(
                alpacaApiService,
                mock(com.investingapp.backend.service.AlpacaService.class),
                mock(com.investingapp.backend.service.PlaidToAlpacaService.class),
                userRepository,
                encryptionService,
                mock(com.investingapp.backend.service.KycSubmissionService.class))).build();

        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private ResultActions upload(String documentType, String mimeType, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("documentType", documentType);
        body.put("mimeType", mimeType);
        body.put("content", content);
        return mvc.perform(post("/api/alpaca/upload-document")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private void nothingWasSent() {
        verify(alpacaApiService, never()).uploadDocument(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a JPEG, a PNG and a PDF of an offered document type are passed on")
    void goodFilesAreAccepted() throws Exception {
        upload("identity_verification", "image/jpeg", b64(JPEG)).andExpect(status().isOk());
        upload("address_verification", "image/png", b64(PNG)).andExpect(status().isOk());
        upload("tax_id_verification", "application/pdf", b64(PDF)).andExpect(status().isOk());
        upload("date_of_birth_verification", "application/pdf", b64(PDF)).andExpect(status().isOk());

        verify(alpacaApiService).uploadDocument("acct-1", "identity_verification", "image/jpeg", b64(JPEG));
    }

    @Test
    @DisplayName("any other file type is refused with a sentence the user can act on")
    void otherFileTypesAreRefused() throws Exception {
        for (String mime : new String[] { "image/heic", "image/gif", "image/svg+xml", "text/html",
                "application/json", "application/x-msdownload", "IMAGE/JPEG " }) {
            upload("identity_verification", mime, b64(JPEG))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        nothingWasSent();
    }

    @Test
    @DisplayName("a document type the app does not offer is refused")
    void otherDocumentTypesAreRefused() throws Exception {
        for (String type : new String[] { "w8ben", "account_approval_letter", "cip_result", "anything", "" }) {
            upload(type, "image/jpeg", b64(JPEG)).andExpect(status().isBadRequest());
        }
        nothingWasSent();
    }

    @Test
    @DisplayName("content that is not base64, or is empty, is refused")
    void contentMustBeBase64() throws Exception {
        upload("identity_verification", "image/jpeg", "not base64 at all !!").andExpect(status().isBadRequest());
        upload("identity_verification", "image/jpeg", "data:image/jpeg;base64," + b64(JPEG))
                .andExpect(status().isBadRequest());
        upload("identity_verification", "image/jpeg", "").andExpect(status().isBadRequest());
        nothingWasSent();
    }

    @Test
    @DisplayName("a file that is not what it claims to be is refused")
    void contentMustMatchItsType() throws Exception {
        upload("identity_verification", "image/jpeg", b64(PDF)).andExpect(status().isBadRequest());
        upload("identity_verification", "application/pdf", b64(PNG)).andExpect(status().isBadRequest());
        upload("identity_verification", "image/png", b64("<html><script>".getBytes()))
                .andExpect(status().isBadRequest());
        nothingWasSent();
    }

    @Test
    @DisplayName("a real PDF that starts with a byte order mark or a blank line is still accepted")
    void pdfHeaderNeedNotBeTheFirstByte() throws Exception {
        byte[] withMark = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '%', 'P', 'D', 'F', '-', '1', '.', '4', '\n' };
        upload("identity_verification", "application/pdf", b64(withMark)).andExpect(status().isOk());
        upload("identity_verification", "application/pdf", b64("\r\n\r\n%PDF-1.7\n".getBytes())).andExpect(status().isOk());
        // But not something that merely mentions it far into the file.
        byte[] late = new byte[2000];
        System.arraycopy("%PDF-".getBytes(), 0, late, 1500, 5);
        upload("identity_verification", "application/pdf", b64(late)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("more than 10 MB of encoded content is refused as too large")
    void oversizeContentIsRefused() throws Exception {
        // The broker's limit is on the ENCODED size: 10 MB of base64 text.
        StringBuilder content = new StringBuilder(b64(JPEG));
        content.append("A".repeat(10 * 1024 * 1024 + 4 - content.length()));
        upload("identity_verification", "image/jpeg", content.toString())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").isNotEmpty());
        nothingWasSent();
    }

    @Test
    @DisplayName("a refusal by the broker is answered with a fixed sentence, never its text")
    void brokerTextNeverReachesTheReply() throws Exception {
        Map<String, Object> refused = new HashMap<>();
        refused.put("error", "Failed to upload document: 422 LEAKMARK {\"account\":\"acct-1\"}");
        when(alpacaApiService.uploadDocument(anyString(), anyString(), anyString(), anyString())).thenReturn(refused);

        String body = upload("identity_verification", "image/jpeg", b64(JPEG))
                .andExpect(status().is4xxClientError()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("LEAKMARK").doesNotContain("acct-1");
    }
}
