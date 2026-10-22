package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A Plaid Item waiting for /item/remove (spec 3.9). Carries its own encrypted token copy because a
 * relink overwrites users.plaid_access_token before the old Item is removed.
 */
@Entity
@Table(name = "plaid_item_removals")
@Getter
@Setter
@NoArgsConstructor
public class PlaidItemRemoval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** No foreign key: the row outlives the user. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "item_id", nullable = false, length = 80)
    private String itemId;

    @Column(name = "access_token_enc", nullable = false, length = 512)
    private String accessTokenEnc;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
