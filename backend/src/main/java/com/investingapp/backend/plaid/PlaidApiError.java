package com.investingapp.backend.plaid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * One non-2xx Plaid answer, or a transport failure, reduced to what FRED branches and logs on.
 *
 * <p>error_message and display_message are deliberately not kept: they are free text (spec 12.3)
 * and can name an institution. Every component here is a Plaid code, a Plaid request id or a
 * number. The body is parsed with Jackson JsonNode (the PlaidWebhookInboxService pattern), not
 * with Jackson into the SDK's PlaidError, whose Gson names and enum adapters Jackson cannot map.
 *
 * @param errorType       Plaid error_type, or {@link #TRANSPORT_ERROR_TYPE} for an IOException, or null
 * @param errorCode       Plaid error_code, or null when none parsed
 * @param errorCodeReason Plaid error_code_reason, or null
 * @param requestId       Plaid request_id from the error body, or null
 * @param httpStatus      the HTTP status, 0 for a transport failure
 * @param errorClass      {@link PlaidErrorClass#classify(String, int)} of the two above
 */
public record PlaidApiError(String errorType, String errorCode, String errorCodeReason, String requestId,
        int httpStatus, PlaidErrorClass errorClass) {

    /** errorType of the synthetic error that stands for an IOException (no HTTP answer at all). */
    public static final String TRANSPORT_ERROR_TYPE = "TRANSPORT";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parses a Plaid error body that the caller has already read once; a missing or broken body keeps the status. */
    public static PlaidApiError fromBody(int httpStatus, String body) {
        JsonNode root = parse(body);
        String errorCode = text(root, "error_code");
        return new PlaidApiError(text(root, "error_type"), errorCode, text(root, "error_code_reason"),
                text(root, "request_id"), httpStatus, PlaidErrorClass.classify(errorCode, httpStatus));
    }

    /** The IOException case: no answer, so no code, and TRANSIENT (spec 2.1, contract T06). */
    public static PlaidApiError transport() {
        return new PlaidApiError(TRANSPORT_ERROR_TYPE, null, null, null, 0, PlaidErrorClass.TRANSIENT);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            return root == null ? MissingNode.getInstance() : root;
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
