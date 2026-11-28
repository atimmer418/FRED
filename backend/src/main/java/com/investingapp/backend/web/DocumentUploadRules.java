package com.investingapp.backend.web;

import org.springframework.http.HttpStatus;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * What an identity document must be before it is passed on to the broker.
 *
 * <p>The limits are the broker's own (Alpaca Broker API, POST
 * /v1/accounts/{account_id}/documents/upload): the content is base64, its type is a PDF, PNG or
 * JPEG, and "each encoded content size is limited to 10MB". The document types are the four the
 * app offers, a subset of the broker's list.
 *
 * <p>The file is also checked to be what it says it is, by its first bytes, so a web page or a
 * program cannot be passed off as a photo.
 */
public final class DocumentUploadRules {

    /** The broker's limit on the ENCODED content, in characters of base64. */
    public static final int MAX_ENCODED_CHARACTERS = 10 * 1024 * 1024;

    public static final Set<String> DOCUMENT_TYPES = Set.of(
            "identity_verification", "address_verification", "date_of_birth_verification", "tax_id_verification");

    public static final Set<String> MIME_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    /** A refusal: the status to answer with and a sentence for the user. */
    public record Refusal(HttpStatus status, String message) {
    }

    private DocumentUploadRules() {
    }

    /** Empty when the document may be sent on; otherwise why not. */
    public static Optional<Refusal> check(String documentType, String mimeType, String content) {
        if (content == null || content.isEmpty()) {
            return refuse(HttpStatus.BAD_REQUEST, "Please choose a file to upload.");
        }
        if (content.length() > MAX_ENCODED_CHARACTERS) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "That file is too large. Please choose one under 7 MB.");
        }
        if (documentType == null || !DOCUMENT_TYPES.contains(documentType)) {
            return refuse(HttpStatus.BAD_REQUEST, "Please choose what kind of document this is.");
        }
        if (mimeType == null || !MIME_TYPES.contains(mimeType)) {
            return refuse(HttpStatus.BAD_REQUEST, "Please upload a JPEG, PNG or PDF.");
        }
        if (!isBase64(content)) {
            return refuse(HttpStatus.BAD_REQUEST, "That file could not be read. Please try again.");
        }
        if (!startsLike(mimeType, firstBytes(content))) {
            return refuse(HttpStatus.BAD_REQUEST, "That file is not a valid JPEG, PNG or PDF.");
        }
        return Optional.empty();
    }

    private static Optional<Refusal> refuse(HttpStatus status, String message) {
        return Optional.of(new Refusal(status, message));
    }

    /** Standard base64 only: the alphabet, padding at the very end, a whole number of groups. */
    private static boolean isBase64(String content) {
        int length = content.length();
        if (length % 4 != 0) {
            return false;
        }
        int padding = content.endsWith("==") ? 2 : content.endsWith("=") ? 1 : 0;
        for (int i = 0; i < length - padding; i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The first decoded bytes (about 1 KB at most); only the start of the content is decoded. */
    private static byte[] firstBytes(String content) {
        String head = content.substring(0, Math.min(content.length(), 1368)); // a multiple of 4
        try {
            return Base64.getDecoder().decode(head);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static boolean startsLike(String mimeType, byte[] bytes) {
        return switch (mimeType) {
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            // The PDF format lets its header sit anywhere in the first 1024 bytes, and real files
            // do start with a byte order mark or a blank line. Readers accept those; so does this.
            case "application/pdf" -> containsPdfHeader(bytes);
            default -> false;
        };
    }

    private static boolean containsPdfHeader(byte[] bytes) {
        int last = Math.min(bytes.length, 1024) - 5;
        for (int i = 0; i <= last; i++) {
            if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D' && bytes[i + 3] == 'F' && bytes[i + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
