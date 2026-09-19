package com.investingapp.backend.dto;

import java.time.LocalDate;
import java.util.List;

/** TLH enrollment + ledger + wash-window status for the requesting user. */
public record TlhStatusResponse(
        boolean enrolled,
        boolean tierEligible,
        String mode,
        String ledgerStatus,
        List<WindowDto> activeWindows,
        List<String> unreconciledSymbols,
        int inFlightEvents,
        String disclaimer) {

    public record WindowDto(String symbol, LocalDate endDate, String redirectSymbol) {
    }
}
