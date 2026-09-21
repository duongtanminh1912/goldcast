package vn.goldcast.api.dto;

import vn.goldcast.domain.Instrument;

/**
 * An instrument as the frontend needs it — including the unit label and display scale, so
 * formatting rules live in one place instead of being re-derived in TypeScript.
 */
public record InstrumentDto(
        String code,
        String name,
        String kind,
        String currency,
        String unit,
        String unitLabel,
        String region,
        String source,
        boolean hasSpread,
        int displayScale) {

    public static InstrumentDto from(Instrument instrument) {
        return new InstrumentDto(
                instrument.getCode(),
                instrument.getName(),
                instrument.getKind().name(),
                instrument.getCurrency(),
                instrument.getUnit().name(),
                instrument.getUnit().label(),
                instrument.getRegion(),
                instrument.getSource(),
                instrument.isHasSpread(),
                instrument.getUnit().displayScale());
    }
}
