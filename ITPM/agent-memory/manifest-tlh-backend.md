# Acceptance Check Manifest — TLH Backend (Piggy Pro Tax Loss Harvesting)

Scope: backend-only Tax Loss Harvesting engine, Piggy Pro gated, portfolio-agnostic.
All code uncommitted in the working tree. Run tests from /backend with:
`./gradlew test --tests '<FullyQualifiedClass>'`

NOTE: `com.investingapp.backend.BackendApplicationTests` (Spring context load) fails
with "Failed to determine suitable jdbc url" on the UNMODIFIED tree too (verified via
git stash) — environmental, needs live MySQL env vars; exclude from the verdict.

VERIFIER RUN (2026-07-15): `./gradlew compileJava` clean. All 12 TLH test classes green
selectively — 105 tests, 0 failures, 0 errors. Full `./gradlew test` = 200 completed,
1 failed (only the documented BackendApplicationTests jdbc failure). Static + adversarial
review found no in-scope failures. Verdict: APPROVED.

## AC-1: Piggy Pro tier guard at every entry point
- Type:     backend-unit
- Check:    Non-pro tiers (null/core/plus, and off-case 'Pro') get 403 on enroll/preview/run;
            scheduler eligibility queries findBySelectedTierInAndTlhEnabledTrue(["pro"]);
            read endpoints allow pro OR users with TLH history only.
- Evidence: TlhControllerTest (nonProTiersGet403OnWriteEndpoints, tierMatchIsExactAndCaseSensitiveFailingClosed,
            summaryAllowsDowngradedUserWithHistoryButNotWithout) — all present & green (12/12).
            Confirmed in code: TlhController.isPro() exact "pro" match; TlhHarvestService.runCycle
            query findBySelectedTierInAndTlhEnabledTrue(["pro"]) + ACTIVE filter; TlhExecutionService.submitSell
            re-checks tier == "pro" at execution moment (TIER_LOST veto).
- Status:   pass

## AC-2: Wash-sale enforcement in both directions incl. recurring buys and DRIP
- Type:     backend-unit
- Check:    Window committed before order exists; date-boundary contract (blocked through endDate,
            legal endDate+1); identity-sibling expansion (VOO window blocks SPY);
            scheduled slices redirect/skip; DRIP parks as cash or redirects; explicit funded
            user lump-sums ALLOW with nightly disallowance; request-time stock buys 400 before ACH.
- Evidence: WashSaleGuardServiceTest (13 tests incl. dateBoundaryContractBlockedThroughEndDateLegalDayAfter,
            identityGroupExpansionBlocksSiblings, scheduledBuyRedirectsToWindowTarget,
            dripDefaultsToSkipAndLumpSumIsAlwaysAllowed, tlhOwnBuysAndFutureRebalancerAreHardBlocked),
            DripServiceTlhGuardTest (3), InvestmentExecutionServiceTlhGuardTest (3),
            TlhHarvestServiceCycleTest.liveClaimCommitsWindowThenDelegatesToExecution — all green.
            Code: openProvisionalWindow in same claim tx (before order); InvestmentController
            catches IllegalArgumentException -> 400 before ACH; guard hooks at all 3 buy choke points.
- Status:   pass

## AC-3: FIFO-faithful loss realization (no gain-bearing harvests by construction)
- Type:     backend-unit
- Check:    Full-position-only sells gated on EVERY open lot underwater (price-buffered);
            one above-water lot vetoes the position; partial fills get §1091 disallowance
            accounting; net-gain fills become COMPLETED_GAIN, window canceled, excluded from savings.
- Evidence: TaxLotLedgerServiceTest (previewOneAboveWaterLotDefeatsTheWholePosition, washDisallowance* — 13 green),
            TlhEligibilityServiceTest (12 green; VETO_NOT_ALL_LOTS_UNDERWATER + VETO_SHARES_HELD gates),
            TlhExecutionServiceTest.gainAtFillCancelsWindowAndSchedulesPromptReentry (16 green).
            Code: previewFifoSale allUnderwater with bufferFactor; candidate qty = full position;
            handleSellFill netPnl>=0 -> COMPLETED_GAIN + cancelWindow + allowedLoss 0.
- Status:   pass

## AC-4: Ledger single source of truth (no double disposal)
- Type:     backend-unit
- Check:    Poller-applied synthetic order fills and nightly activity ingestion dedup against
            each other by activity id AND order id; oversell marks symbol UNRECONCILED durably.
- Evidence: TaxLotLedgerServiceTest (duplicateActivityIdIsNoOp, activityWhoseOrderWasPollerAppliedIsSkipped,
            syntheticReapplicationIsSkipped, oversellMarksSymbolUnreconciled) — all present & green.
            Code: isDuplicate() checks activityId + orderId both directions; DB unique constraints
            uq_tax_lots_source_activity and uq_disposals_activity_lot back it; FIFO OrderByAcquiredAtAsc.
- Status:   pass

## AC-5: Provable idempotency + crash recovery
- Type:     backend-unit
- Check:    UNIQUE (user,symbol,cycle_date,mode) claim; duplicate claim is a skip; client_order_id
            persisted BEFORE every HTTP call; adopt-or-fail by client id; lookup-failure is never
            terminal; stale SELL_PENDING recovered by poller.
- Evidence: TlhHarvestServiceCycleTest.duplicateClaimIsAnIdempotentSkip,
            TlhExecutionServiceTest (submitFailureWithOrderFoundByClientIdIsAdopted,
            submitFailureConfirmedAbsentFailsCleanlyAndCancelsWindow,
            submitFailureWithLookupFailureLeavesSellPendingForThePoller,
            orphanedSellPendingIsAdoptedOrFailedByThePoller, replacementPersistsPerAttemptClientIdBeforeSubmitting)
            — all present & green. Code: @UniqueConstraint uq_tlh_events_claim (all 4 cols NOT NULL);
            ddl-auto=update creates it; OrderLookupResult found/confirmedAbsent/lookupFailed tri-state.
- Status:   pass

## AC-6: Safety posture — mode gates and fail-closed clock
- Type:     backend-unit
- Check:    mode=off is a complete no-op; dry-run persists PROPOSED rows with numbers, zero orders,
            and NO wash window; clock closed/unreachable skips the cycle; config defaults to off
            in base properties, dry-run in local/dev, off in ci.
- Evidence: TlhHarvestServiceCycleTest (modeOffIsACompleteNoOp, closedOrUnreachableClockSkipsTheCycleFailClosed,
            dryRunPersistsProposedWithNumbersAndZeroOrdersAndNoWindow) — all present & green.
            Config verified: application.properties app.tlh.mode=${TLH_MODE:off}; ci=off; dev=dry-run;
            local=dry-run. Code: runCycle returns before cycle-run row on off; claimHarvest skips
            openProvisionalWindow on dry-run; getMarketClock() any-error -> false.
- Status:   pass

## AC-7: Portfolio-agnostic + generalized replacement policy
- Type:     backend-unit
- Check:    Arbitrary symbols (AAPL) evaluate through identical machinery; curated pairs walk in
            rank order with universal vetting (windowed/recent-loss-sale/inactive/same-cycle/sibling);
            unmapped symbols follow skip|hold-cash policy; single stocks never get an auto-picked
            "similar" stock; IJS/SLYV same-index pair is structurally impossible.
- Evidence: TlhEligibilityServiceTest.arbitrarySymbolsFlowThroughIdenticalMachinery,
            ReplacementSelectionServiceTest (8 green), TlhCatalogTest (sameIndexSmallCapValueFundsAreNeverEachOthersCandidates,
            pairInsideAnIdentityGroupFailsStartup) — all present & green.
            Code: TlhCatalog @PostConstruct throws if a pair falls inside an identity group;
            replacementCandidates empty for unmapped -> skip/hold-cash; IJS+SLYV both in SP600_VALUE group.
- Status:   pass

## AC-8: Summary integrity ("saved this year")
- Type:     backend-unit
- Check:    Sums ONLY allowedLoss (realized − disallowed) of LIVE events per tax year;
            estimatedSavings = sum × rate HALF_UP; disclaimer present and avoids the
            MarketNarrativeValidator-blocked "rebalance your" phrasing.
- Evidence: TlhSummaryServiceTest (4 green). Code: sumAllowedLossForYear JPQL filters
            e.mode = 'LIVE' and taxYear (enum-string-literal pattern matches existing repo precedent:
            BeneficiaryRepository status='APPROVED', InvestmentExecutionRepository status='COMPLETED');
            savings = allowed.multiply(rate) HALF_UP; DISCLAIMER contains no "rebalance your".
- Status:   pass

## AC-9: Build + full-suite regression gate
- Type:     backend-unit
- Check:    ./gradlew compileJava clean; ./gradlew test → only pre-existing environmental
            BackendApplicationTests failure (jdbc url); all 12 TLH classes green (104 tests);
            all pre-existing unit tests still green.
- Evidence: gradle output — compileJava BUILD SUCCESSFUL; full `test` = "200 tests completed, 1 failed"
            (only BackendApplicationTests.contextLoads DataSourceBeanCreationException, the documented
            env exclusion); 12 TLH classes selectively = 105 tests, 0 fail, 0 err (1 more than the
            expected 104 — a test was added, still all green).
- Status:   pass
