package com.investingapp.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RecoveryResponse {
    private boolean success;
    private String message;
    /**
     * Set only when a recovery code was accepted. Good for registering one new passkey and nothing
     * else: it is not a session token and no API accepts it as one.
     */
    private String recoveryToken;
}
