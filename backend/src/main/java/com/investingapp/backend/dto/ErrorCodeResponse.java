package com.investingapp.backend.dto;

/** Machine-readable 409 body: clients branch on code and show message. */
public record ErrorCodeResponse(String code, String message) {
}
