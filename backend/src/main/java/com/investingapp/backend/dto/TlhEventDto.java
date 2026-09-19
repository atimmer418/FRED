package com.investingapp.backend.dto;

import com.investingapp.backend.model.TlhHarvestEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One harvest event, audit-grade. Loss figures are positive magnitudes. */
public record TlhEventDto(
        Long id,
        String symbol,
        String replacementSymbol,
        String status,
        String mode,
        LocalDate cycleDate,
        BigDecimal quantitySold,
        BigDecimal realizedLoss,
        BigDecimal disallowedLoss,
        BigDecimal allowedLoss,
        BigDecimal actualProceeds,
        String sellOrderId,
        String replacementOrderId,
        LocalDateTime sellFilledAt,
        LocalDateTime replacementFilledAt,
        String vetoReason,
        boolean basisEstimated) {

    public static TlhEventDto from(TlhHarvestEvent event) {
        return new TlhEventDto(
                event.getId(),
                event.getSymbol(),
                event.getReplacementSymbol(),
                event.getStatus() == null ? null : event.getStatus().name(),
                event.getMode() == null ? null : event.getMode().name(),
                event.getCycleDate(),
                event.getQuantitySold(),
                event.getRealizedLoss(),
                event.getDisallowedLoss(),
                event.getAllowedLoss(),
                event.getActualProceeds(),
                event.getSellOrderId(),
                event.getReplacementOrderId(),
                event.getSellFilledAt(),
                event.getReplacementFilledAt(),
                event.getVetoReason(),
                Boolean.TRUE.equals(event.getBasisEstimated()));
    }
}
