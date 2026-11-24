package com.investingapp.backend.paycheck.detect;

/** Spec 4.5 score rows; toJson() fills score_breakdown_json. */
public record ScoreBreakdown(int pfc, int plaidStatus, int median, int cadenceFit, int stability, int payrollToken, int total) {

    public String toJson() {
        return "{\"pfc\":" + pfc + ",\"plaidStatus\":" + plaidStatus + ",\"median\":" + median + ",\"cadenceFit\":" + cadenceFit
                + ",\"stability\":" + stability + ",\"payrollToken\":" + payrollToken + ",\"total\":" + total + "}";
    }
}
