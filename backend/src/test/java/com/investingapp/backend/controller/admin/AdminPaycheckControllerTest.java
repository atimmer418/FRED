package com.investingapp.backend.controller.admin;

import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.AdminPaycheckService;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import com.investingapp.backend.service.ShadowReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPaycheckControllerTest {

    private final AdminPaycheckService service = mock(AdminPaycheckService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(audit.actor(any(HttpServletRequest.class))).thenReturn("ops-test");
        mvc = MockMvcBuilders.standaloneSetup(new AdminPaycheckController(service, audit, mock(ShadowReportService.class))).build();
    }

    @Test
    void holdPassesReasonNoteAndActorToTheService() throws Exception {
        when(service.hold("ops-test", 7L, "BANK_CHANGE", "relink")).thenReturn(VerbResult.ok(Map.of("changed", true)));

        mvc.perform(post("/api/admin/paycheck/7/hold").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BANK_CHANGE\",\"note\":\"relink\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void refusalAnswersItsStatusAndCode() throws Exception {
        when(service.clearReview("ops-test", 7L, null)).thenReturn(VerbResult.refusal(409, "ACCOUNT_REJECTED", "stays"));

        mvc.perform(post("/api/admin/paycheck/7/clear-review"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
    }
}
