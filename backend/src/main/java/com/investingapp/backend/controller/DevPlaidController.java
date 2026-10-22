package com.investingapp.backend.controller;

import com.investingapp.backend.service.PlaidItemLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-profile Plaid tooling. Registered only in local/dev/test/ci, guarded by DevToolsKeyFilter
 * (X-Dev-Tools-Key) and refused at startup in prod by DevToolsProdGuard.
 */
@RestController
@RequestMapping("/api/dev/plaid")
@CrossOrigin(origins = "*")
@Profile({"local", "dev", "test", "ci"})
public class DevPlaidController {

    private final PlaidItemLifecycleService lifecycleService;

    public DevPlaidController(PlaidItemLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /** Phase 0 backfill: /item/webhook/update for every stored token. Run once per environment. */
    @PostMapping("/backfill-webhook-update")
    public ResponseEntity<PlaidItemLifecycleService.BackfillReport> backfill() {
        return ResponseEntity.ok(lifecycleService.backfillWebhookUrl());
    }
}
