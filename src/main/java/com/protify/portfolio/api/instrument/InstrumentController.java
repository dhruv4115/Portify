package com.protify.portfolio.api.instrument;

import com.protify.portfolio.api.dto.InstrumentResponse;
import com.protify.portfolio.api.mapper.InstrumentMapper;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.instrument.InstrumentRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The type-ahead for tomorrow's add form (day-2-dev-C.md D2-C3). Not user-scoped — the
 * instrument catalogue is shared — so no {@code CurrentUserResolver} here, deliberately.
 * Delegates straight to {@link InstrumentRepository}; there is no {@code InstrumentService}
 * (nothing here is business logic, just a search-and-map).
 */
@RestController
@RequestMapping("/instruments")
@Validated
public class InstrumentController {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentMapper instrumentMapper;

    public InstrumentController(InstrumentRepository instrumentRepository, InstrumentMapper instrumentMapper) {
        this.instrumentRepository = instrumentRepository;
        this.instrumentMapper = instrumentMapper;
    }

    @GetMapping
    public List<InstrumentResponse> search(
            @RequestParam @NotBlank @Size(min = 1, max = 50) String query,
            @RequestParam(required = false) AssetType assetType,
            @RequestParam(required = false) CurrencyCode currency,
            @RequestParam(defaultValue = "20") @Max(50) int limit) {
        return instrumentRepository.search(query, assetType, currency, limit).stream()
                .map(instrumentMapper::toResponse)
                .toList();
    }
}
