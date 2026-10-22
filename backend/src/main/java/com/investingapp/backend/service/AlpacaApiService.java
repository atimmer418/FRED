package com.investingapp.backend.service;

import com.investingapp.backend.dto.CreateAlpacaAccountRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import org.springframework.web.client.RestClientException;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

@Service
public class AlpacaApiService {

    private static final Logger logger = LoggerFactory.getLogger(AlpacaApiService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AlpacaService alpacaService;
    private final String apiKey;
    private final String apiSecret;
    private final String brokerBaseUrl;
    private final String tradingBaseUrl;

    public AlpacaApiService(
            @Value("${alpaca.api.key}") String apiKey,
            @Value("${alpaca.api.secret}") String apiSecret,
            @Value("${alpaca.broker.base-url:https://broker-api.sandbox.alpaca.markets/v1}") String brokerBaseUrl,
            @Value("${alpaca.trading.base-url:https://paper-api.alpaca.markets/v2}") String tradingBaseUrl,
            AlpacaService alpacaService) {
        this.restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.brokerBaseUrl = brokerBaseUrl;
        this.tradingBaseUrl = tradingBaseUrl;
        this.alpacaService = alpacaService;

        // Log configuration (without exposing secrets)
        logger.info("AlpacaApiService initialized:");
        logger.info("  Broker Base URL: {}", brokerBaseUrl);
        logger.info("  Trading Base URL: {}", tradingBaseUrl);
        logger.info("  API Key: {}",
                apiKey != null ? apiKey.substring(0, Math.min(8, apiKey.length())) + "..." : "null");
        logger.info("  API Secret: {}", apiSecret != null ? "***set***" : "null");
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Use HTTP Basic authentication for Broker API
        String credentials = apiKey + ":" + apiSecret;
        String base64Credentials = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + base64Credentials);

        return headers;
    }

    public String getAccountInfo() {
        HttpEntity<String> entity = new HttpEntity<>(createHeaders());
        ResponseEntity<String> response = restTemplate.exchange(
                brokerBaseUrl + "/accounts",
                HttpMethod.GET,
                entity,
                String.class);
        return response.getBody();
    }

    /**
     * @deprecated Use {@link #createAccount(CreateAlpacaAccountRequest, String)} instead,
     *             which sends the full KYC payload with real disclosures and all 3 agreements.
     */
    @Deprecated
    public Map<String, Object> createAccount(String email, String firstName, String lastName,
            String dateOfBirth, String ssn, String phone,
            Map<String, String> address) {
        logger.info("Creating Alpaca account for user: {}", email);

        // Add parameter validation and logging
        logger.debug(
                "Parameters received - email: {}, firstName: {}, lastName: {}, dateOfBirth: {}, ssn: {}, phone: {}, address: {}",
                email, firstName, lastName, dateOfBirth, ssn, phone, address);

        if (address != null) {
            logger.debug("Address details - street_address: {}, city: {}, state: {}, postal_code: {}",
                    address.get("street_address"), address.get("city"), address.get("state"),
                    address.get("postal_code"));
        }

        if (email == null || firstName == null || lastName == null) {
            logger.error("Required parameters are null - email: {}, firstName: {}, lastName: {}", email, firstName,
                    lastName);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Required parameters cannot be null: email, firstName, lastName");
            return errorResult;
        }

        Map<String, Object> accountData = new HashMap<>();

        // Contact information
        Map<String, Object> contactInfo = new HashMap<>();
        contactInfo.put("email_address", email);
        contactInfo.put("phone_number", phone);

        // Add address to contact if provided
        if (address != null) {
            String streetAddress = address.get("street_address"); // Fixed: use correct key
            if (streetAddress != null) {
                contactInfo.put("street_address", List.of(streetAddress));
            }
            contactInfo.put("city", address.get("city"));
            contactInfo.put("state", address.get("state"));
            contactInfo.put("postal_code", address.get("postal_code"));
        }

        // Identity information
        Map<String, Object> identity = new HashMap<>();
        identity.put("given_name", firstName);
        identity.put("family_name", lastName);
        identity.put("date_of_birth", dateOfBirth); // Format: YYYY-MM-DD
        identity.put("tax_id", ssn);
        identity.put("tax_id_type", "USA_SSN");
        identity.put("country_of_citizenship", "USA");
        identity.put("country_of_birth", "USA");
        identity.put("country_of_tax_residence", "USA");
        identity.put("funding_source", List.of("employment_income"));

        // Trusted contact (required)
        Map<String, String> trustedContact = new HashMap<>();
        trustedContact.put("given_name", firstName); // Use same person for simplicity
        trustedContact.put("family_name", lastName);
        trustedContact.put("email_address", email);

        // Build account data
        accountData.put("contact", contactInfo);
        accountData.put("identity", identity);
        accountData.put("trusted_contact", trustedContact);
        accountData.put("disclosures", Map.of(
                "is_control_person", false,
                "is_affiliated_exchange_or_finra", false,
                "is_affiliated_exchange_or_iiroc", false,
                "is_politically_exposed", false,
                "immediate_family_exposed", false));
        accountData.put("agreements", List.of(
                Map.of(
                        "agreement", "customer_agreement",
                        "signed_at", java.time.Instant.now().toString(),
                        "ip_address", "127.0.0.1")));

        // Remove the separate address handling since it's now in contact

        try {
            String jsonBody = objectMapper.writeValueAsString(accountData);
            logger.debug("Alpaca account creation request: {}", jsonBody);

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    brokerBaseUrl + "/accounts",
                    HttpMethod.POST,
                    entity,
                    String.class);

            String responseBody = response.getBody();
            JsonNode responseNode = objectMapper.readTree(responseBody);
            Map<String, Object> result = new HashMap<>();
            String accountId = responseNode.get("id").asText();
            result.put("account_id", accountId);
            if (responseNode.has("account_number")) {
                result.put("account_number", responseNode.get("account_number").asText());
            }
            result.put("status", responseNode.get("status").asText());
            result.put("created_at", responseNode.get("created_at").asText());
            result.put("raw_response", responseBody);

            logger.info("Successfully created Alpaca account: {}", accountId);

            // IMPORTANT: Configure account for cash-only trading (no margin)
            logger.info("Configuring account {} for cash-only trading...", accountId);
            alpacaService.setupCashOnlyAccount(accountId);

            return result;

        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON", e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to process JSON: " + e.getMessage());
            return errorResult;
        } catch (Exception e) {
            logger.error("Error creating Alpaca account for {}: {}", email, e.getMessage());
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to create account: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Create an Alpaca account using the full KYC payload.
     * Agreement timestamps and client IP are captured server-side — the frontend
     * does NOT send these values.
     *
     * @param request         full KYC data from the frontend form
     * @param clientIpAddress the real client IP extracted from HttpServletRequest
     */
    public Map<String, Object> createAccount(CreateAlpacaAccountRequest request, String clientIpAddress) {
        logger.info("Creating Alpaca account (full KYC) for email: {}", request.getEmailAddress());

        String signedAt = Instant.now().toString();

        // Contact
        Map<String, Object> contact = new HashMap<>();
        contact.put("email_address", request.getEmailAddress());
        contact.put("phone_number", request.getPhoneNumber());
        contact.put("street_address", List.of(request.getStreetAddress()));
        contact.put("city", request.getCity());
        contact.put("state", request.getState());
        contact.put("postal_code", request.getPostalCode());
        contact.put("country", "USA");

        // Identity
        Map<String, Object> identity = new HashMap<>();
        identity.put("given_name", request.getGivenName());
        identity.put("family_name", request.getFamilyName());
        identity.put("date_of_birth", request.getDateOfBirth());
        identity.put("tax_id", request.getTaxId().replaceAll("-", ""));
        identity.put("tax_id_type", request.getTaxIdType() != null ? request.getTaxIdType() : "USA_SSN");
        identity.put("country_of_citizenship", "USA");
        identity.put("country_of_birth", "USA");
        identity.put("country_of_tax_residence", "USA");
        identity.put("funding_source", request.getFundingSource());

        // Disclosures
        Map<String, Object> disclosures = new HashMap<>();
        disclosures.put("is_control_person", request.isControlPerson());
        disclosures.put("is_affiliated_exchange_or_finra", request.isAffiliatedExchangeOrFinra());
        disclosures.put("is_affiliated_exchange_or_iiroc", false);
        disclosures.put("is_politically_exposed", request.isPoliticallyExposed());
        disclosures.put("immediate_family_exposed", request.isImmediateFamilyExposed());

        // Agreements — all 3 required by Alpaca, signed server-side
        // TODO: Replace with real Alpaca agreement URLs once provided
        List<Map<String, String>> agreements = List.of(
                Map.of("agreement", "customer_agreement", "signed_at", signedAt, "ip_address", clientIpAddress),
                Map.of("agreement", "margin_agreement",   "signed_at", signedAt, "ip_address", clientIpAddress),
                Map.of("agreement", "account_agreement",  "signed_at", signedAt, "ip_address", clientIpAddress)
        );

        // Trusted contact (use the account holder's own info)
        Map<String, String> trustedContact = new HashMap<>();
        trustedContact.put("given_name", request.getGivenName());
        trustedContact.put("family_name", request.getFamilyName());
        trustedContact.put("email_address", request.getEmailAddress());

        Map<String, Object> accountData = new HashMap<>();
        accountData.put("contact", contact);
        accountData.put("identity", identity);
        accountData.put("disclosures", disclosures);
        accountData.put("agreements", agreements);
        accountData.put("trusted_contact", trustedContact);

        try {
            String jsonBody = objectMapper.writeValueAsString(accountData);
            // Log request without sensitive fields (SSN redacted)
            logger.debug("Alpaca full KYC account creation request length: {} chars", jsonBody.length());

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    brokerBaseUrl + "/accounts",
                    HttpMethod.POST,
                    entity,
                    String.class);

            String responseBody = response.getBody();
            JsonNode responseNode = objectMapper.readTree(responseBody);
            Map<String, Object> result = new HashMap<>();
            String accountId = responseNode.get("id").asText();
            result.put("account_id", accountId);
            if (responseNode.has("account_number")) {
                result.put("account_number", responseNode.get("account_number").asText());
            }
            result.put("status", responseNode.get("status").asText());
            result.put("created_at", responseNode.get("created_at").asText());
            result.put("raw_response", responseBody);

            logger.info("Successfully created Alpaca account (full KYC): {}", accountId);

            // Configure account for cash-only trading (no margin)
            logger.info("Configuring account {} for cash-only trading...", accountId);
            alpacaService.setupCashOnlyAccount(accountId);

            return result;

        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON for full KYC account creation", e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to process JSON: " + e.getMessage());
            return errorResult;
        } catch (Exception e) {
            logger.error("Error creating Alpaca account (full KYC) for {}: {}", request.getEmailAddress(), e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to create account: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Upload a KYC document to Alpaca for an account that requires additional
     * verification (ACTION_REQUIRED status).
     *
     * @param accountId      the Alpaca account ID
     * @param documentType   Alpaca document type (e.g. identity_verification)
     * @param mimeType       MIME type of the file (e.g. image/jpeg, application/pdf)
     * @param base64Content  base64-encoded file content (without the data: URI prefix)
     */
    public Map<String, Object> uploadDocument(String accountId, String documentType,
            String mimeType, String base64Content) {
        logger.info("Uploading document type {} for account {}", documentType, accountId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("document_type", documentType);
            // document_sub_type omitted — let Alpaca infer from document_type
            payload.put("content", base64Content);
            payload.put("mime_type", mimeType);

            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    brokerBaseUrl + "/accounts/" + accountId + "/documents/upload",
                    HttpMethod.POST,
                    entity,
                    String.class);

            String responseBody = response.getBody();
            logger.info("Successfully uploaded document for account {}", accountId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("raw_response", responseBody);
            return result;

        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON for document upload", e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to process JSON: " + e.getMessage());
            return errorResult;
        } catch (Exception e) {
            logger.error("Error uploading document for account {}: {}", accountId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to upload document: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Get tradeable assets from Alpaca
     * Note: Assets endpoint is typically public and doesn't require authentication
     */
    public String getAssets(String status, String assetClass, String search) {

        // Add query parameters
        StringBuilder queryParams = new StringBuilder("?");
        if (status != null && !status.isEmpty()) {
            queryParams.append("status=").append(status).append("&");
        }
        if (assetClass != null && !assetClass.isEmpty()) {
            queryParams.append("asset_class=").append(assetClass).append("&");
        }
        if (search != null && !search.isEmpty()) {
            queryParams.append("search=").append(search).append("&");
        }

        String fullUrl = brokerBaseUrl + "/assets" + queryParams.toString().replaceAll("&$", "");
        logger.info("Fetching assets from: {}", fullUrl);
        logger.info("Using API Key: {}***",
                apiKey != null ? apiKey.substring(0, Math.min(4, apiKey.length())) : "null");

        try {
            HttpEntity<String> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.GET,
                    entity,
                    String.class);

            String responseBody = response.getBody();
            logger.info("Assets API response status: {}, body length: {}",
                    response.getStatusCode(),
                    responseBody != null ? responseBody.length() : 0);

            if (responseBody != null && responseBody.length() > 100) {
                logger.debug("Assets API response preview: {}", responseBody.substring(0, 100) + "...");
            }

            return responseBody;
        } catch (Exception e) {
            logger.error("Error calling Alpaca assets API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch assets from Alpaca: " + e.getMessage(), e);
        }
    }

    /**
     * Create ACH relationship for an Alpaca account using bank details
     */
    public Map<String, Object> createAchRelationship(String accountId, String accountOwnerName,
            String bankAccountType, String bankAccountNumber,
            String bankRoutingNumber, String nickname) {
        logger.info("Creating ACH relationship for account: {}", accountId);

        try {
            Map<String, Object> achData = new HashMap<>();
            achData.put("account_owner_name", accountOwnerName);
            achData.put("bank_account_type", bankAccountType.toUpperCase()); // CHECKING or SAVINGS
            achData.put("bank_account_number", bankAccountNumber);
            achData.put("bank_routing_number", bankRoutingNumber);
            achData.put("nickname", nickname);

            String jsonBody = objectMapper.writeValueAsString(achData);
            logger.debug("ACH relationship creation request: {}", jsonBody);

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    brokerBaseUrl + "/accounts/" + accountId + "/ach_relationships",
                    HttpMethod.POST,
                    entity,
                    String.class);

            String responseBody = response.getBody();
            JsonNode responseNode = objectMapper.readTree(responseBody);
            Map<String, Object> result = new HashMap<>();
            result.put("id", responseNode.get("id").asText());
            result.put("status", responseNode.get("status").asText());
            result.put("created_at", responseNode.get("created_at").asText());
            result.put("raw_response", responseBody);
            logger.info("Successfully created ACH relationship: {}", result.get("id"));
            return result;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                // Spec 7.8: a 409 means a relationship still exists. Never adopt it; the 15-minute poll retries.
                logger.warn("ACH relationship create returned 409 for account {}; waiting for cancellation", accountId);
                return Map.<String, Object>of(
                        "error", "Alpaca returned 409: an ACH relationship already exists; waiting for cancellation",
                        "conflict", true);
            }
            logger.error("Error creating ACH relationship for account {}: {}", accountId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to create ACH relationship: " + e.getMessage());
            return errorResult;
        } catch (Exception e) {
            logger.error("Error creating ACH relationship for account {}: {}", accountId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to create ACH relationship: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Get documents for an account (Tax statements, Trade confirmations, etc.)
     */
    public String getAccountDocuments(String accountId) {
        return getAccountDocuments(accountId, null, null);
    }

    public String getAccountDocuments(String accountId, String start, String end) {
        StringBuilder urlBuilder = new StringBuilder(brokerBaseUrl + "/accounts/" + accountId + "/documents");

        if (start != null || end != null) {
            urlBuilder.append("?");
            if (start != null) {
                urlBuilder.append("start=").append(start).append("&");
            }
            if (end != null) {
                urlBuilder.append("end=").append(end).append("&");
            }
        }

        String url = urlBuilder.toString();
        if (url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }

        HttpEntity<String> entity = new HttpEntity<>(createHeaders());
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (Exception e) {
            logger.error("Error fetching documents for account {}: {}", accountId, e.getMessage());
            throw new RuntimeException("Failed to fetch documents", e);
        }
    }

    /**
     * Download a specific document
     */
    public byte[] downloadDocument(String accountId, String documentId) {
        String url = brokerBaseUrl + "/accounts/" + accountId + "/documents/" + documentId + "/download";
        
        // Create headers specifically for binary download
        HttpHeaders headers = new HttpHeaders();
        String credentials = apiKey + ":" + apiSecret;
        String base64Credentials = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + base64Credentials);
        headers.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_OCTET_STREAM));
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        try {
            logger.info("Downloading document {} for account {} from URL: {}", documentId, accountId, url);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            
            byte[] documentBytes = response.getBody();
            if (documentBytes == null || documentBytes.length == 0) {
                logger.error("Received empty document from Alpaca API");
                throw new RuntimeException("Document is empty");
            }
            
            // Check if it's actually a PDF by looking at the first few bytes
            if (documentBytes.length > 5) {
                String header = new String(documentBytes, 0, Math.min(5, documentBytes.length));
                logger.info("Document header: {}, size: {} bytes", header, documentBytes.length);
                
                if (!header.startsWith("%PDF-")) {
                    // Log first 200 chars to see what we actually got
                    String preview = new String(documentBytes, 0, Math.min(200, documentBytes.length));
                    logger.error("Downloaded content is not a PDF! Preview: {}", preview);
                    throw new RuntimeException("Downloaded file is not a valid PDF");
                }
            }
            
            logger.info("Successfully downloaded PDF document, size: {} bytes", documentBytes.length);
            return documentBytes;
        } catch (Exception e) {
            logger.error("Error downloading document {} for account {}: {}", documentId, accountId, e.getMessage());
            throw new RuntimeException("Failed to download document", e);
        }
    }

    /**
     * Get ACH relationships for an account
     */
    public String getAchRelationships(String accountId) {
        logger.debug("Getting ACH relationships for account: {}", accountId);

        HttpEntity<String> entity = new HttpEntity<>(createHeaders());
        ResponseEntity<String> response = restTemplate.exchange(
                brokerBaseUrl + "/accounts/" + accountId + "/ach_relationships",
                HttpMethod.GET,
                entity,
                String.class);
        return response.getBody();
    }

    /**
     * DELETE one ACH relationship. 204 and 404 both mean "gone", which is all a caller needs; any
     * other outcome is logged and reported as false so the caller can retry on the next poll.
     */
    public boolean deleteAchRelationship(String accountId, String relationshipId) {
        String url = brokerBaseUrl + "/accounts/" + accountId + "/ach_relationships/" + relationshipId;
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(createHeaders()), String.class);
            logger.info("ACH relationship {} delete requested for account {}", relationshipId, accountId);
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return true;
            }
            logger.error("ACH relationship delete refused rel={} account={}: {}", relationshipId, accountId, e.getMessage());
            return false;
        } catch (RestClientException e) {
            logger.error("ACH relationship delete failed rel={} account={}: {}", relationshipId, accountId, e.getMessage());
            return false;
        }
    }

    /**
     * GET the account's ACH relationships filtered server-side by status. Rows without an id are
     * skipped so a caller can always encrypt and store what it gets back. Transport failures
     * propagate as RestClientException; callers decide whether to swallow them.
     */
    public List<AchRelationshipSummary> listAchRelationships(String accountId, List<String> statuses) {
        String url = brokerBaseUrl + "/accounts/" + accountId + "/ach_relationships?statuses=" + String.join(",", statuses);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(createHeaders()), String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "[]" : response.getBody());
            List<AchRelationshipSummary> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode rel : root) {
                    String id = rel.path("id").asText(null);
                    if (id == null) {
                        continue;
                    }
                    result.add(new AchRelationshipSummary(id, rel.path("status").asText(null),
                            rel.path("created_at").asText(null), rel.path("bank_account_number").asText(null)));
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable ACH relationship list for account " + accountId, e);
        }
    }

    /**
     * Get account status by account ID
     */
    public String getAccountStatus(String accountId) {
        HttpEntity<String> entity = new HttpEntity<>(createHeaders());
        ResponseEntity<String> response = restTemplate.exchange(
                brokerBaseUrl + "/accounts/" + accountId,
                HttpMethod.GET,
                entity,
                String.class);
        return response.getBody();
    }

    public String getAccountNumber(String accountId) {
        try {
            String response = getAccountStatus(accountId);
            JsonNode root = objectMapper.readTree(response);
            if (root.has("account_number")) {
                return root.get("account_number").asText();
            }
        } catch (Exception e) {
            logger.error("Error fetching account number for id: {}", accountId, e);
        }
        return null;
    }

    /**
     * Fetch full KYC/account data for a given Alpaca account ID.
     * Returns the raw JSON from Alpaca's GET /v1/accounts/{account_id}.
     */
    public String getAccountKyc(String accountId) {
        HttpEntity<String> entity = new HttpEntity<>(createHeaders());
        ResponseEntity<String> response = restTemplate.exchange(
                brokerBaseUrl + "/accounts/" + accountId,
                HttpMethod.GET,
                entity,
                String.class);
        return response.getBody();
    }

    /**
     * Update KYC fields for a given Alpaca account using PATCH /v1/accounts/{account_id}.
     * Only fields present in the request map are included.
     * Returns the raw JSON response from Alpaca.
     */
    public String patchAccountKyc(String accountId, com.investingapp.backend.dto.UpdateKycRequest request) {
        try {
            // Build partial update — only include non-null fields
            Map<String, Object> contact = new HashMap<>();
            if (request.getEmailAddress() != null) contact.put("email_address", request.getEmailAddress());
            if (request.getPhoneNumber() != null)   contact.put("phone_number", request.getPhoneNumber());
            if (request.getStreetAddress() != null) contact.put("street_address", List.of(request.getStreetAddress()));
            if (request.getCity() != null)           contact.put("city", request.getCity());
            if (request.getState() != null)          contact.put("state", request.getState());
            if (request.getPostalCode() != null)     contact.put("postal_code", request.getPostalCode());

            Map<String, Object> identity = new HashMap<>();
            if (request.getGivenName() != null)     identity.put("given_name", request.getGivenName());
            if (request.getFamilyName() != null)    identity.put("family_name", request.getFamilyName());
            if (request.getFundingSource() != null) identity.put("funding_source", request.getFundingSource());

            Map<String, Object> disclosures = new HashMap<>();
            if (request.getIsControlPerson() != null)
                disclosures.put("is_control_person", request.getIsControlPerson());
            if (request.getIsAffiliatedExchangeOrFinra() != null)
                disclosures.put("is_affiliated_exchange_or_finra", request.getIsAffiliatedExchangeOrFinra());
            if (request.getIsPoliticallyExposed() != null)
                disclosures.put("is_politically_exposed", request.getIsPoliticallyExposed());
            if (request.getImmediateFamilyExposed() != null)
                disclosures.put("immediate_family_exposed", request.getImmediateFamilyExposed());

            Map<String, Object> body = new HashMap<>();
            if (!contact.isEmpty())     body.put("contact", contact);
            if (!identity.isEmpty())    body.put("identity", identity);
            if (!disclosures.isEmpty()) body.put("disclosures", disclosures);

            String jsonBody = objectMapper.writeValueAsString(body);
            logger.info("Patching KYC for account {}: {} top-level keys", accountId, body.size());

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    brokerBaseUrl + "/accounts/" + accountId,
                    HttpMethod.PATCH,
                    entity,
                    String.class);

            logger.info("KYC patch response status for account {}: {}", accountId, response.getStatusCode());
            return response.getBody();

        } catch (JsonProcessingException e) {
            logger.error("JSON error patching KYC for account {}", accountId, e);
            throw new RuntimeException("Failed to serialize KYC update: " + e.getMessage(), e);
        }
    }
}