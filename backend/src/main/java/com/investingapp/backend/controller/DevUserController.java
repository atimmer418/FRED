package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * DEV ONLY admin verb for user deletion (spec 7.9; the prod admin surface is a later phase).
 * Registered only in local/dev/test/ci; every request needs X-Dev-Tools-Key (DevToolsKeyFilter on
 * /api/dev/*), and DevToolsProdGuard refuses to start prod if this bean is ever registered.
 */
@RestController
@RequestMapping("/api/dev/users")
@CrossOrigin(origins = "*")
@Profile({"local", "dev", "test", "ci"})
public class DevUserController {

    private static final Logger logger = LoggerFactory.getLogger(DevUserController.class);

    private final UserRepository userRepository;
    private final UserDeletionService userDeletionService;

    public DevUserController(UserRepository userRepository, UserDeletionService userDeletionService) {
        this.userRepository = userRepository;
        this.userDeletionService = userDeletionService;
    }

    /** POST /api/dev/users/{id}/delete: 200 deleted, 404 unknown id, 409 DELETION_DEFERRED. */
    @PostMapping("/{id}/delete")
    public ResponseEntity<?> deleteUser(@PathVariable("id") Long id) {
        Optional<User> user = userRepository.findById(id);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            userDeletionService.delete(user.get(), UserDeletionService.DeletionReason.ADMIN);
        } catch (DeletionDeferredException deferred) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("DELETION_DEFERRED", deferred.reason));
        }
        logger.warn("[DevUserController] deleted user id={} reason=ADMIN", id);
        return ResponseEntity.ok(Map.of("deleted", true, "userId", id));
    }
}
