package com.investingapp.backend.controller.admin;

import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.ShadowReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPaycheckShadowReportTest {

    @Test
    void shadowReportIsAuditedAsAGet() throws Exception {
        ShadowReportService shadowReports = mock(ShadowReportService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        Constructor<?> constructor = AdminPaycheckController.class.getConstructors()[0];
        assertThat(AdminPaycheckController.class.getConstructors()).hasSize(1);
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == ShadowReportService.class ? shadowReports : types[i] == AdminAuditService.class ? audit : mock(types[i]);
        }
        AdminPaycheckController controller = (AdminPaycheckController) constructor.newInstance(args);
        LocalDate since = LocalDate.of(2026, 10, 28);
        ShadowReport report = new ShadowReport(since, LocalDate.of(2026, 11, 4), true, new ShadowReport.Summary(0, 0, 0, 0, 0, 0.0), List.of());
        when(shadowReports.build(since)).thenReturn(report);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/paycheck/shadow-report");
        when(audit.actor(request)).thenReturn("andy");

        ResponseEntity<ShadowReport> response = controller.shadowReport(since, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(report);
        verify(audit).record("andy", null, AdminPaycheckController.VERB_SHADOW_REPORT, null, null, Map.of("users", 0, "shadowCycles", 0), null);
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
    }
}
