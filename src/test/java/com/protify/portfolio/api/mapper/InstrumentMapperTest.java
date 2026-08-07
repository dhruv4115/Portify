package com.protify.portfolio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.api.dto.InstrumentResponse;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.instrument.Instrument;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstrumentMapperTest {

    private final InstrumentMapper mapper = new InstrumentMapper();

    @Test
    void mapsEveryField() {
        Instrument instrument = new Instrument(
                12L, "RELIANCE", "Reliance Industries Ltd", AssetType.STOCK, CurrencyCode.INR,
                "NSE", "Energy", Instant.EPOCH);

        InstrumentResponse response = mapper.toResponse(instrument);

        assertThat(response.id()).isEqualTo(12L);
        assertThat(response.symbol()).isEqualTo("RELIANCE");
        assertThat(response.name()).isEqualTo("Reliance Industries Ltd");
        assertThat(response.assetType()).isEqualTo(AssetType.STOCK);
        assertThat(response.currency()).isEqualTo(CurrencyCode.INR);
        assertThat(response.exchange()).isEqualTo("NSE");
        assertThat(response.sector()).isEqualTo("Energy");
    }
}
