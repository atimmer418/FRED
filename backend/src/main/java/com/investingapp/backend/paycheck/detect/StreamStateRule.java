package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PaycheckStreamState;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Spec 4.5 CANDIDATE, PROVISIONAL or CONFIRMED; other states are decided elsewhere. */
public final class StreamStateRule {

    public static PaycheckStreamState state(int score, int onCadenceDeposits, boolean plaidMature, boolean plaidStreamKnown,
            PaycheckAutopilotProperties props) {
        if (onCadenceDeposits < 2 || score < props.getScoreProvisional()) { return PaycheckStreamState.CANDIDATE; }
        boolean evidence = (onCadenceDeposits >= 3 && plaidMature) || (onCadenceDeposits >= 4 && !plaidStreamKnown);
        return score >= props.getScoreConfirmed() && evidence ? PaycheckStreamState.CONFIRMED : PaycheckStreamState.PROVISIONAL;
    }

    public static int onCadenceCount(List<LocalDate> postedDatesAsc, CadenceResult cadence) {
        if (postedDatesAsc == null || postedDatesAsc.isEmpty()) { return 0; }
        int[] band = CadenceDeriver.band(cadence.cadence());
        int count = 1;
        for (int i = 1; i < postedDatesAsc.size(); i++) {
            long gap = ChronoUnit.DAYS.between(postedDatesAsc.get(i - 1), postedDatesAsc.get(i));
            if (gap >= band[0] && gap <= band[1]) { count++; }
        }
        return count;
    }
}
