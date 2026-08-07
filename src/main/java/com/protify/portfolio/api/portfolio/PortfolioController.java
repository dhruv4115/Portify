package com.protify.portfolio.api.portfolio;

import com.protify.portfolio.api.dto.CreatePortfolioRequest;
import com.protify.portfolio.api.dto.PortfolioResponse;
import com.protify.portfolio.api.dto.UpdatePortfolioRequest;
import com.protify.portfolio.api.error.ConflictException;
import com.protify.portfolio.api.mapper.PortfolioMapper;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Validates, delegates to {@link PortfolioService}, maps — no business logic here
 * (day-2-dev-C.md D2-C2). Every method resolves {@code userId} from
 * {@link CurrentUserResolver} first and passes it into every service call; no query ever runs
 * without it. {@link PortfolioService#getOrThrow} (used by {@code get} and transitively by
 * {@code delete}) already filters by {@code userId}, which is why another user's portfolio
 * surfaces as {@link com.protify.portfolio.common.error.NotFoundException} — 404, never 403.
 */
@RestController
@RequestMapping("/portfolios")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final PortfolioMapper portfolioMapper;
    private final PortfolioSummaryProvider summaryProvider;
    private final CurrentUserResolver currentUserResolver;

    public PortfolioController(
            PortfolioService portfolioService,
            PortfolioMapper portfolioMapper,
            PortfolioSummaryProvider summaryProvider,
            CurrentUserResolver currentUserResolver) {
        this.portfolioService = portfolioService;
        this.portfolioMapper = portfolioMapper;
        this.summaryProvider = summaryProvider;
        this.currentUserResolver = currentUserResolver;
    }

    /** Never 404 — an empty list is a normal answer, not an error. */
    @GetMapping
    public List<PortfolioResponse> list() {
        long userId = currentUserResolver.resolve().id();
        return portfolioService.findAllByUser(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    public ResponseEntity<PortfolioResponse> create(@Valid @RequestBody CreatePortfolioRequest request) {
        long userId = currentUserResolver.resolve().id();
        Portfolio created;
        try {
            created = portfolioService.create(userId, request.name(), request.baseCurrency());
        } catch (DuplicateKeyException e) {
            throw new ConflictException(
                    "/errors/duplicate-portfolio-name",
                    "A portfolio named '%s' already exists.".formatted(request.name()));
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @GetMapping("/{id}")
    public PortfolioResponse get(@PathVariable long id) {
        long userId = currentUserResolver.resolve().id();
        Portfolio portfolio = portfolioService.getOrThrow(userId, id);
        return toResponse(portfolio);
    }

    /**
     * API_CONTRACT.md §5. {@code name} and {@code baseCurrency} are each optional, but at least
     * one is required — that cross-field rule isn't expressible as a single Bean Validation
     * annotation, so it's checked here rather than invented as a one-off constraint class for a
     * single caller. Changing {@code baseCurrency} rewrites no {@code txn}/{@code holding} row
     * ({@code PortfolioRepository#update}, {@code BaseCurrencyImmutabilityIT}) — only how
     * totals are presented, resolved fresh on the next read through
     * {@link ValuationBackedPortfolioSummaryProvider}.
     */
    @PatchMapping("/{id}")
    public PortfolioResponse update(@PathVariable long id, @Valid @RequestBody UpdatePortfolioRequest request) {
        if (request.name() == null && request.baseCurrency() == null) {
            throw new ValidationException(
                    "portfolio-update-empty", "At least one of name or baseCurrency must be provided.");
        }

        long userId = currentUserResolver.resolve().id();
        Portfolio updated;
        try {
            updated = portfolioService.update(userId, id, request.name(), request.baseCurrency());
        } catch (DuplicateKeyException e) {
            throw new ConflictException(
                    "/errors/duplicate-portfolio-name",
                    "A portfolio named '%s' already exists.".formatted(request.name()));
        }
        return toResponse(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        long userId = currentUserResolver.resolve().id();
        portfolioService.delete(userId, id);
    }

    private PortfolioResponse toResponse(Portfolio portfolio) {
        return portfolioMapper.toResponse(portfolio, summaryProvider.summarize(portfolio));
    }
}
