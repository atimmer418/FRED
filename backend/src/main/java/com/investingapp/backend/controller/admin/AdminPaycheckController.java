package com.investingapp.backend.controller.admin;

import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.AdminPaycheckService;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/paycheck")
public class AdminPaycheckController {

    public record HoldRequest(String reason, String note) {
    }

    public record NoteRequest(String note) {
    }

    private final AdminPaycheckService service;
    private final AdminAuditService adminAuditService;

    public AdminPaycheckController(AdminPaycheckService service, AdminAuditService adminAuditService) {
        this.service = service;
        this.adminAuditService = adminAuditService;
    }

    @GetMapping("/{userId}/status")
    public ResponseEntity<Object> status(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.status(actor(request), userId));
    }

    @GetMapping("/{userId}/plaid-item")
    public ResponseEntity<Object> plaidItem(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.plaidItem(actor(request), userId));
    }

    @PostMapping("/{userId}/sync")
    public ResponseEntity<Object> sync(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.sync(actor(request), userId));
    }

    @PostMapping("/{userId}/evaluate")
    public ResponseEntity<Object> evaluate(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.evaluate(actor(request), userId));
    }

    @PostMapping("/{userId}/reconcile")
    public ResponseEntity<Object> reconcile(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.reconcile(actor(request), userId));
    }

    @PostMapping("/{userId}/hold")
    public ResponseEntity<Object> hold(@PathVariable("userId") Long userId, @RequestBody HoldRequest body,
                                       HttpServletRequest request) {
        return respond(service.hold(actor(request), userId, body.reason(), body.note()));
    }

    @PostMapping("/{userId}/clear-review")
    public ResponseEntity<Object> clearReview(@PathVariable("userId") Long userId,
                                              @RequestBody(required = false) NoteRequest body,
                                              HttpServletRequest request) {
        return respond(service.clearReview(actor(request), userId, note(body)));
    }

    @PostMapping("/{userId}/promote-stream/{streamId}")
    public ResponseEntity<Object> promoteStream(@PathVariable("userId") Long userId,
                                                @PathVariable("streamId") Long streamId,
                                                @RequestBody(required = false) NoteRequest body,
                                                HttpServletRequest request) {
        return respond(service.promoteStream(actor(request), userId, streamId, note(body)));
    }

    @PostMapping("/{userId}/retire-stream/{streamId}")
    public ResponseEntity<Object> retireStream(@PathVariable("userId") Long userId,
                                               @PathVariable("streamId") Long streamId,
                                               @RequestBody(required = false) NoteRequest body,
                                               HttpServletRequest request) {
        return respond(service.retireStream(actor(request), userId, streamId, note(body)));
    }

    @PostMapping("/{userId}/cycles/{cycleId}/void")
    public ResponseEntity<Object> voidCycle(@PathVariable("userId") Long userId,
                                            @PathVariable("cycleId") Long cycleId,
                                            @RequestBody(required = false) NoteRequest body,
                                            HttpServletRequest request) {
        return respond(service.voidCycle(actor(request), userId, cycleId, note(body)));
    }

    @GetMapping("/webhooks")
    public ResponseEntity<Object> webhooks(@RequestParam(name = "status", defaultValue = "FAILED") String status,
                                           HttpServletRequest request) {
        return respond(service.webhooks(actor(request), status));
    }

    @PostMapping("/webhooks/{eventId}/replay")
    public ResponseEntity<Object> replay(@PathVariable("eventId") Long eventId, HttpServletRequest request) {
        return respond(service.replay(actor(request), eventId));
    }

    private String actor(HttpServletRequest request) {
        return adminAuditService.actor(request);
    }

    private static String note(NoteRequest body) {
        return body == null ? null : body.note();
    }

    private static ResponseEntity<Object> respond(VerbResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
