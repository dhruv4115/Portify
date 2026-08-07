package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.InstrumentResponse;
import com.protify.portfolio.instrument.Instrument;
import org.springframework.stereotype.Component;

/** Explicit, hand-written mapping — no MapStruct, no reflection (day-2-dev-C.md D2-C1). */
@Component
public class InstrumentMapper {

    public InstrumentResponse toResponse(Instrument instrument) {
        return new InstrumentResponse(
                instrument.id(),
                instrument.symbol(),
                instrument.name(),
                instrument.assetType(),
                instrument.currency(),
                instrument.exchange(),
                instrument.sector());
    }
}
