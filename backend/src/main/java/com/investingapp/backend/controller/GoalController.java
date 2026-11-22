package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.GoalImportRequest;
import com.investingapp.backend.dto.GoalRequest;
import com.investingapp.backend.dto.GoalResponse;
import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.GoalService;
import com.investingapp.backend.service.GoalValidationException;
import com.investingapp.backend.service.TierLimitException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private static final Logger logger = LoggerFactory.getLogger(GoalController.class);

    private final GoalService goalService;
    private final UserRepository userRepository;

    public GoalController(GoalService goalService, UserRepository userRepository) {
        this.goalService = goalService;
        this.userRepository = userRepository;
    }

    @GetMapping("")
    public ResponseEntity<?> list() {
        return withUser(user -> ResponseEntity.ok(goalService.listForUser(user)));
    }

    @PostMapping("")
    public ResponseEntity<?> create(@Valid @RequestBody GoalRequest request) {
        return withUser(user -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(user, goalService.create(user, request))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody GoalRequest request) {
        return withUser(user -> ResponseEntity.ok(toResponse(user, goalService.update(user, id, request))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return withUser(user -> {
            goalService.delete(user, id);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/import")
    public ResponseEntity<?> importLegacy(@Valid @RequestBody GoalImportRequest request) {
        return withUser(user -> ResponseEntity.ok(goalService.toResponses(user, goalService.importLegacy(user, request))));
    }

    /** Rejects bodies that fail bean validation with a 400 and the first field message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MessageResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first != null && first.getDefaultMessage() != null
                ? first.getField() + " " + first.getDefaultMessage()
                : "Invalid goal request";
        return ResponseEntity.badRequest().body(new MessageResponse(message));
    }

    /** One goal, rolled forward and carrying its tally. */
    private GoalResponse toResponse(User user, Goal goal) {
        return goalService.toResponses(user, List.of(goal)).get(0);
    }

    /** Resolves the caller, runs the action, and maps service failures to HTTP statuses. */
    private ResponseEntity<?> withUser(Function<User, ResponseEntity<?>> action) {
        User user = getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("User not authenticated"));
        }
        try {
            return action.apply(user);
        } catch (GoalValidationException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        } catch (TierLimitException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorCodeResponse("TIER_LIMIT", "Piggy Plus unlocks more than one goal."));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("Goal not found"));
        } catch (Exception e) {
            logger.error("Goal request failed for user {}", user.getId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error saving goal"));
        }
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            return null;
        }
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        return userRepository.findById(userDetails.getId()).orElse(null);
    }
}
