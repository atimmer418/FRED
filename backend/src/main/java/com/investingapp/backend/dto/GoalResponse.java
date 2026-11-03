package com.investingapp.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.model.goals.GoalConfigCodec;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoalResponse {

    private static final String TIMESTAMP = "yyyy-MM-dd'T'HH:mm:ss";

    private Long id;
    private String clientId;
    private GoalType type;
    private String name;
    private String icon;
    /** The parsed config record, so the wire form is a JSON object. */
    private Object config;
    private BigDecimal currentBalance;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = TIMESTAMP)
    private LocalDateTime balanceUpdatedAt;

    private BigDecimal anchorBalance;
    private BigDecimal anchorMonthly;
    private BigDecimal planRate;
    private String planRateKind;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = TIMESTAMP)
    private LocalDateTime planAnchoredAt;

    private boolean completed;
    private int sortOrder;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = TIMESTAMP)
    private LocalDateTime createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = TIMESTAMP)
    private LocalDateTime updatedAt;

    public GoalResponse(Goal goal, GoalConfigCodec codec) {
        this.id = goal.getId();
        this.clientId = goal.getClientId();
        this.type = goal.getType();
        this.name = goal.getName();
        this.icon = goal.getIcon();
        this.config = codec.parse(goal.getType(), goal.getConfigJson());
        this.currentBalance = goal.getCurrentBalance();
        this.balanceUpdatedAt = goal.getBalanceUpdatedAt();
        this.anchorBalance = goal.getAnchorBalance();
        this.anchorMonthly = goal.getAnchorMonthly();
        this.planRate = goal.getPlanRate();
        this.planRateKind = goal.getPlanRateKind();
        this.planAnchoredAt = goal.getPlanAnchoredAt();
        this.completed = goal.isCompleted();
        this.sortOrder = goal.getSortOrder();
        this.createdAt = goal.getCreatedAt();
        this.updatedAt = goal.getUpdatedAt();
    }
}
