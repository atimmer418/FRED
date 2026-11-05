package com.investingapp.backend.controller.admin;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The admin delete verb (spec 7.9, 12.2); the audit row rides the deletion transaction's hook and outlives the user. No @CrossOrigin. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    static final String VERB_DELETE = "users.delete";

    private final UserRepository userRepository;
    private final UserDeletionService userDeletionService;
    private final AdminAuditService adminAuditService;

    public AdminUserController(UserRepository userRepository, UserDeletionService userDeletionService,
            AdminAuditService adminAuditService) {
        this.userRepository = userRepository;
        this.userDeletionService = userDeletionService;
        this.adminAuditService = adminAuditService;
    }

    /** 200 deleted, 404 unknown id, 409 DELETION_DEFERRED; every outcome audited. */
    @PostMapping("/{id}/delete")
    public ResponseEntity<?> deleteUser(@PathVariable("id") Long id,
            @RequestParam(value = "reason", required = false) String reason, HttpServletRequest request) {
        String actor = adminAuditService.actor(request);
        Optional<User> found = userRepository.findById(id);
        if (found.isEmpty()) {
            adminAuditService.record(actor, id, VERB_DELETE, null, null, Map.of("outcome", "NOT_FOUND"), reason);
            return ResponseEntity.notFound().build();
        }
        User user = found.get();
        String publicId = user.getPublicId();
        Map<String, Object> before = snapshot(user);
        try {
            userDeletionService.delete(user, UserDeletionService.DeletionReason.ADMIN,
                    () -> adminAuditService.recordInTransaction(actor, id, VERB_DELETE, publicId, before,
                            Map.of("outcome", "DELETED"), reason));
        } catch (DeletionDeferredException deferred) {
            adminAuditService.record(actor, id, VERB_DELETE, publicId, before,
                    Map.of("outcome", "DEFERRED", "deferral", deferred.reason), reason);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("DELETION_DEFERRED", deferred.reason));
        } catch (RuntimeException failed) {
            adminAuditService.record(actor, id, VERB_DELETE, publicId, before, Map.of("outcome", "FAILED"), reason);
            throw failed;
        }
        return ResponseEntity.ok(Map.of("deleted", true, "userId", id));
    }

    /** Closed vocabulary only: no email, no name, no token. */
    private static Map<String, Object> snapshot(User user) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("accountStatus", user.getAccountStatus());
        state.put("hasPlaidItem", user.getPlaidAccessToken() != null);
        state.put("plaidItemStatus", user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name());
        return state;
    }
}
