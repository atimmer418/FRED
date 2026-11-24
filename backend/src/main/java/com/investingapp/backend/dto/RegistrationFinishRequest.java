package com.investingapp.backend.dto;

import com.fasterxml.jackson.databind.JsonNode; // <-- IMPORT THIS
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull; // <-- IMPORT THIS
import lombok.Data;

@Data
public class RegistrationFinishRequest {

    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email should be valid")
    private String email;

    // This now correctly represents the complex object sent from the frontend
    @NotNull(message = "Credential object cannot be null")
    private JsonNode credential; // <-- CHANGE THIS FROM String to JsonNode

    /**
     * Only for the passkey that follows account recovery: the one-time token from
     * POST /api/auth/recovery/verify. Absent on a normal sign-up.
     */
    private String recoveryToken;
}