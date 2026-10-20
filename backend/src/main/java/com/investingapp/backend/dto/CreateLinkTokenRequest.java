package com.investingapp.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /api/plaid/create_link_token body: "link" (default) for a new Item, "update" for the stored one. */
@Data
@NoArgsConstructor
public class CreateLinkTokenRequest {
    private String mode;
}
