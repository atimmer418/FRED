package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.GoalImportRequest;
import com.investingapp.backend.dto.GoalLedgerRequest;
import com.investingapp.backend.dto.GoalRequest;
import com.investingapp.backend.dto.GoalResponse;
import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.goals.GoalConfigCodec;
import com.investingapp.backend.model.goals.TargetConfig;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.GoalService;
import com.investingapp.backend.service.GoalValidationException;
import com.investingapp.backend.service.TierLimitException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoalControllerTest {

    private GoalService service;
    private UserRepository userRepository;
    private GoalConfigCodec codec;
    private User user;
    private GoalController controller;

    @BeforeEach
    void setUp() {
        service = mock(GoalService.class);
        userRepository = mock(UserRepository.class);
        codec = new GoalConfigCodec(new ObjectMapper().registerModule(new JavaTimeModule()));

        user = new User();
        user.setId(7L);
        user.setEmail("andy@fredvested.com");
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(7L);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        controller = new GoalController(service, userRepository);
    }

    private List<GoalResponse> responses(Goal... goals) {
        return java.util.Arrays.stream(goals).map(g -> new GoalResponse(g, codec)).toList();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Goal goal(long id) {
        Goal g = new Goal();
        g.setId(id);
        g.setUser(user);
        g.setClientId("c-" + id);
        g.setType(GoalType.CUSTOM);
        g.setName("Trip");
        g.setIcon("flag");
        g.setConfigJson(codec.write(new TargetConfig(new BigDecimal("5000"), 2030)));
        g.setCurrentBalance(new BigDecimal("100"));
        g.setAnchorBalance(new BigDecimal("100"));
        g.setAnchorMonthly(new BigDecimal("50"));
        g.setPlanRate(new BigDecimal("0.0700"));
        g.setPlanRateKind("market");
        g.setPlanAnchoredAt(LocalDateTime.of(2026, 11, 2, 9, 30, 0));
        g.setCreatedAt(LocalDateTime.of(2026, 11, 2, 9, 30, 0));
        g.setUpdatedAt(LocalDateTime.of(2026, 11, 2, 9, 30, 0));
        return g;
    }

    private GoalRequest request() {
        GoalRequest r = new GoalRequest();
        r.setType(GoalType.CUSTOM);
        r.setName("Trip");
        r.setIcon("flag");
        return r;
    }

    @Test
    void listReturns200WithGoals() {
        when(service.listForUser(user)).thenReturn(responses(goal(1L), goal(2L)));

        ResponseEntity<?> response = controller.list();

        assertEquals(200, response.getStatusCode().value());
        List<?> body = assertInstanceOf(List.class, response.getBody());
        assertEquals(2, body.size());
        GoalResponse first = assertInstanceOf(GoalResponse.class, body.get(0));
        assertEquals(1L, first.getId());
        assertEquals(new TargetConfig(new BigDecimal("5000"), 2030), first.getConfig());
    }

    @Test
    void createReturns201() {
        when(service.create(any(User.class), any(GoalRequest.class))).thenReturn(new GoalResponse(goal(3L), codec));

        ResponseEntity<?> response = controller.create(request());

        assertEquals(201, response.getStatusCode().value());
        assertEquals(3L, assertInstanceOf(GoalResponse.class, response.getBody()).getId());
    }

    @Test
    void createMapsTierLimitTo403WithCode() {
        when(service.create(any(User.class), any(GoalRequest.class)))
                .thenThrow(new TierLimitException("limit"));

        ResponseEntity<?> response = controller.create(request());

        assertEquals(403, response.getStatusCode().value());
        ErrorCodeResponse body = assertInstanceOf(ErrorCodeResponse.class, response.getBody());
        assertEquals("TIER_LIMIT", body.code());
        assertEquals("Piggy Plus unlocks more than one goal.", body.message());
    }

    @Test
    void createMapsValidationTo400() {
        when(service.create(any(User.class), any(GoalRequest.class)))
                .thenThrow(new GoalValidationException("Bad icon"));

        ResponseEntity<?> response = controller.create(request());

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Bad icon", assertInstanceOf(MessageResponse.class, response.getBody()).getMessage());
    }

    @Test
    void updateMapsNotFoundTo404() {
        when(service.update(any(User.class), anyLong(), any(GoalRequest.class)))
                .thenThrow(new NoSuchElementException("Goal not found"));

        ResponseEntity<?> response = controller.update(999999L, request());

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Goal not found", assertInstanceOf(MessageResponse.class, response.getBody()).getMessage());
    }

    @Test
    void deleteReturns204() {
        ResponseEntity<?> response = controller.delete(5L);

        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
        verify(service).delete(user, 5L);
    }

    @Test
    void unauthenticatedReturns401() {
        SecurityContextHolder.clearContext();

        ResponseEntity<?> response = controller.list();

        assertEquals(401, response.getStatusCode().value());
        assertEquals("User not authenticated",
                assertInstanceOf(MessageResponse.class, response.getBody()).getMessage());
    }

    @Test
    void importReturns200WithSavedGoals() {
        when(service.importLegacy(any(User.class), any(GoalImportRequest.class)))
                .thenReturn(responses(goal(8L)));

        GoalImportRequest body = new GoalImportRequest();
        body.setGoals(List.of());
        ResponseEntity<?> response = controller.importLegacy(body);

        assertEquals(200, response.getStatusCode().value());
        List<?> saved = assertInstanceOf(List.class, response.getBody());
        assertEquals(1, saved.size());
    }

    @Test
    void timestampsSerializeAsIsoStrings() throws Exception {
        when(service.listForUser(user)).thenReturn(responses(goal(1L)));
        ObjectMapper wireMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(wireMapper))
                .build();

        mvc.perform(get("/api/goals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].planAnchoredAt").isString())
                .andExpect(jsonPath("$[0].planAnchoredAt").value(Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2}T.*")))
                .andExpect(jsonPath("$[0].createdAt").isString())
                .andExpect(jsonPath("$[0].config.targetYear").value(2030));
    }

    @Test
    void invalidBodyIsRejectedAt400BeforeReachingService() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CUSTOM\",\"name\":\"Trip\",\"icon\":\"flag\",\"config\":{},"
                                + "\"anchorMonthly\":0,\"planRate\":0.07,\"planRateKind\":\"market\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsAKidsBodyWithTheNewProperties() throws Exception {
        String kidsConfig = "{\"brokerageTarget\":0,\"plan529Target\":0,\"firstYearFund\":true,\"kids\":[{"
                + "\"label\":\"Baby\",\"birthYear\":null,\"birthMonth\":null,\"firstYearTarget\":16000,"
                + "\"firstYearDueYear\":2029,\"firstYearDueMonth\":11,\"accounts\":{"
                + "\"brokerage\":{\"balance\":0,\"balanceUpdatedAt\":null,\"anchorBalance\":null,\"anchorMonthly\":0},"
                + "\"plan529\":{\"balance\":0,\"balanceUpdatedAt\":null,\"anchorBalance\":null,\"anchorMonthly\":0},"
                + "\"firstYear\":{\"balance\":0,\"balanceUpdatedAt\":null,\"anchorBalance\":null,\"anchorMonthly\":616}}}]}";
        Goal saved = goal(4L);
        saved.setType(GoalType.KIDS);
        saved.setIcon("child_care");
        saved.setConfigJson(kidsConfig);
        when(service.create(any(User.class), any(GoalRequest.class))).thenReturn(new GoalResponse(saved, codec));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"KIDS\",\"name\":\"Kids\",\"icon\":\"child_care\","
                                + "\"currentBalance\":0,\"anchorMonthly\":616,\"planRate\":0.07,"
                                + "\"planRateKind\":\"market\",\"config\":" + kidsConfig + "}"))
                .andExpect(status().isCreated());
        verify(service).create(any(User.class), any(GoalRequest.class));
    }

    private static final String LEDGER_BODY = "{\"unit\":\"goal\",\"kind\":\"missed\"}";

    @Test
    void ledgerPostReturnsUpdatedGoal() throws Exception {
        when(service.adjust(any(User.class), anyLong(), any(GoalLedgerRequest.class)))
                .thenReturn(new GoalService.AdjustedGoal(new GoalResponse(goal(3L), codec), true));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals/3/ledger").contentType(MediaType.APPLICATION_JSON).content(LEDGER_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fred-Capped", "true"))
                .andExpect(jsonPath("$.id").value(3));
    }

    @Test
    void ledgerPostOmitsCappedHeaderWhenNotCapped() throws Exception {
        when(service.adjust(any(User.class), anyLong(), any(GoalLedgerRequest.class)))
                .thenReturn(new GoalService.AdjustedGoal(new GoalResponse(goal(3L), codec), false));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals/3/ledger").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit\":\"goal\",\"kind\":\"set\",\"amount\":120.50}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Fred-Capped"));
    }

    @Test
    void ledgerPostUnknownUnitIs400() throws Exception {
        when(service.adjust(any(User.class), anyLong(), any(GoalLedgerRequest.class)))
                .thenThrow(new IllegalArgumentException("Unknown goal unit: nope"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals/3/ledger").contentType(MediaType.APPLICATION_JSON).content(LEDGER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UNIT"));
    }

    @Test
    void ledgerPostOtherUsersGoalIs404() throws Exception {
        when(service.adjust(any(User.class), anyLong(), any(GoalLedgerRequest.class)))
                .thenThrow(new NoSuchElementException("Goal not found"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals/99/ledger").contentType(MediaType.APPLICATION_JSON).content(LEDGER_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void ledgerPostFinishedUnitIs409() throws Exception {
        when(service.adjust(any(User.class), anyLong(), any(GoalLedgerRequest.class)))
                .thenThrow(new IllegalStateException("finished"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals/3/ledger").contentType(MediaType.APPLICATION_JSON).content(LEDGER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIT_FINISHED"));
    }

    @Test
    void ledgerPostCreditKindIs400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/goals/3/ledger").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit\":\"goal\",\"kind\":\"credit\",\"amount\":5}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
