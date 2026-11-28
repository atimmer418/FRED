package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shuts a FRED account whose brokerage account is already closed but whose row cannot be deleted
 * yet: a transfer landed between the pre-check and the delete, or the delete itself failed.
 *
 * <p>Without this the user stayed signed in, could sign in again and could recover the account,
 * with no brokerage account behind any of it. Shutting it means, in one transaction: mark the row
 * CLOSED, remove every passkey, remove every session. From then on no token is honoured, no
 * sign-in can start a session (UserSessionService.start), recovery sends no code, and the status
 * poller leaves the row alone. The row and its money records stay for an operator, who finishes
 * with POST /api/admin/users/{id}/delete once nothing is in flight.
 */
@Service
public class AccountClosureService {

    private static final Logger logger = LoggerFactory.getLogger(AccountClosureService.class);

    private final UserRepository userRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserSessionRepository userSessionRepository;
    private final TransactionTemplate tx;

    public AccountClosureService(UserRepository userRepository,
            PasskeyCredentialRepository passkeyCredentialRepository,
            UserSessionRepository userSessionRepository,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passkeyCredentialRepository = passkeyCredentialRepository;
        this.userSessionRepository = userSessionRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Marks the account closed and removes every way into it. Safe to call twice.
     *
     * @param onFirstShut runs inside the same transaction, the first time only: for a record of
     *                    the closure that must commit or roll back with it
     */
    public void shut(Long userId, Runnable onFirstShut) {
        tx.executeWithoutResult(status -> {
            passkeyCredentialRepository.deleteAllForUser(userId);
            userSessionRepository.deleteAllByUserId(userId);
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.isClosed()) {
                return;
            }
            onFirstShut.run();
            user.setAccountStatus(User.ACCOUNT_STATUS_CLOSED);
            userRepository.save(user);
        });
        logger.warn("Account shut with its row kept for an operator user={}", userId);
    }
}
