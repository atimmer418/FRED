package com.investingapp.backend.service;

import com.investingapp.backend.dto.CreateAlpacaAccountRequest;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The local half of a KYC submission: everything we persist once the broker has accepted an
 * account, written in one transaction, with the kyc_submitted event written beside it.
 *
 * Why this class exists at all. The persistence used to sit inline in
 * AlpacaController.createAccount, which is not transactional: each setter flushed on its own and an
 * analytics insert next to them could commit while the user update did not, or the reverse. The
 * outbox refuses to write outside a transaction for exactly that reason, so the block had to move
 * somewhere that has one. Only the local persistence moved. The Alpaca HTTP call stays in the
 * controller, ahead of this call and outside the transaction, because holding a database
 * transaction open across a third-party round trip is how connection pools die.
 *
 * What this class deliberately does NOT do: decide whether the user may submit. A second POST to
 * /create-account still creates a second broker account, and the attempt counter here records that
 * it happened without preventing it. See the note on the counter below.
 */
@Service
public class KycSubmissionService {

    /** Where a fresh submission leaves the user, until AccountStatusService polls the broker. */
    static final String STATUS_SUBMITTED = "SUBMITTED";

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final AnalyticsOutboxService analyticsOutboxService;

    public KycSubmissionService(UserRepository userRepository,
                                EncryptionService encryptionService,
                                AnalyticsOutboxService analyticsOutboxService) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.analyticsOutboxService = analyticsOutboxService;
    }

    /**
     * Persists a KYC submission the broker has already accepted, and records kyc_submitted in the
     * same transaction.
     *
     * The user is loaded here rather than passed in so that the entity is managed by the
     * transaction that writes it, and so the attempt counter is read and incremented inside that
     * transaction rather than from a value fetched earlier by the caller.
     *
     * @param userId       the authenticated user, from the JWT. Never an email from the request
     *                     body: that would let a caller write to somebody else's row.
     * @param brokerResult the broker's create-account response. Read for account_id and
     *                     account_number only; both are encrypted at rest.
     * @param request      the submission. Read for the tax id and the legal name only.
     * @return true when a user row was found and updated, false when there was nothing to update.
     *         False is not an error: the caller logs it, exactly as it did before this moved.
     */
    @Transactional
    public boolean persistSubmission(Long userId,
                                     Map<String, Object> brokerResult,
                                     CreateAlpacaAccountRequest request) {
        if (userId == null) {
            return false;
        }
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return false;
        }
        User user = found.get();

        // --- Moved from AlpacaController.createAccount, statement for statement ----------------
        if (brokerResult != null) {
            if (brokerResult.containsKey("account_id")) {
                user.setAlpacaAccountId(encryptionService.encrypt((String) brokerResult.get("account_id")));
            }
            if (brokerResult.containsKey("account_number")) {
                user.setAlpacaAccountNumber(encryptionService.encrypt((String) brokerResult.get("account_number")));
            }
        }
        if (request != null) {
            if (request.getTaxId() != null && !request.getTaxId().isEmpty()) {
                user.setSsn(encryptionService.encrypt(request.getTaxId()));
                user.setSsnHash(encryptionService.hashSsn(request.getTaxId()));
            }
            if (request.getGivenName() != null) {
                user.setFirstName(request.getGivenName());
            }
            if (request.getFamilyName() != null) {
                user.setLastName(request.getFamilyName());
            }
        }
        user.setAccountStatus(STATUS_SUBMITTED);
        // --- End of the moved block ------------------------------------------------------------

        // The two analytics anchors. kyc_submitted_at is what kyc_decided measures the broker's
        // decision time from; users.create_date cannot stand in for it, because that includes all
        // the onboarding drift before KYC.
        int attempt = (user.getKycAttempts() == null ? 0 : user.getKycAttempts()) + 1;
        user.setKycAttempts(attempt);
        user.setKycSubmittedAt(LocalDateTime.now());

        userRepository.save(user);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("attempt", attemptBand(attempt));
        analyticsOutboxService.record(ServerAnalyticsEvent.KYC_SUBMITTED, user, properties);
        return true;
    }

    /**
     * The attempt, banded. The product question is "did this take more than one go", and the answer
     * stops being useful after the third: a long tail of exact counts is a near-unique fingerprint
     * on a small user base, and the handful of people who submit eight times would be identifiable
     * from the number alone.
     *
     * This is not AnalyticsBands.count(), which would answer "2_3" and conflate the second attempt
     * with the third, and it is not a raw integer. It lives here rather than in AnalyticsBands
     * because no other event asks this question; move it there if a second one ever does.
     */
    static String attemptBand(int attempt) {
        if (attempt <= 1) {
            return "1";
        }
        return attempt == 2 ? "2" : "3_plus";
    }
}
