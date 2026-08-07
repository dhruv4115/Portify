package com.protify.portfolio.portfolio;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Thin orchestration over {@link PortfolioRepository} — translates "not found" for callers
 * that need a portfolio to exist rather than an {@code Optional}. */
@Service
public class PortfolioService {

    private final PortfolioRepository repository;

    public PortfolioService(PortfolioRepository repository) {
        this.repository = repository;
    }

    public List<Portfolio> findAllByUser(long userId) {
        return repository.findAllByUser(userId);
    }

    public Portfolio getOrThrow(long userId, long portfolioId) {
        return repository.findByIdAndUser(userId, portfolioId)
                .orElseThrow(() -> new NotFoundException("portfolio", portfolioId));
    }

    public Portfolio create(long userId, String name, CurrencyCode baseCurrency) {
        return repository.create(userId, name, baseCurrency);
    }

    /**
     * day-4-dev-C.md D4-C4. {@code name}/{@code baseCurrency} are each optional at the call
     * site (the controller has already rejected "both absent"); whichever is {@code null} here
     * keeps the portfolio's current value rather than being written as {@code null}.
     */
    public Portfolio update(long userId, long portfolioId, String name, CurrencyCode baseCurrency) {
        Portfolio existing = getOrThrow(userId, portfolioId);
        String effectiveName = name != null ? name : existing.name();
        CurrencyCode effectiveCurrency = baseCurrency != null ? baseCurrency : existing.baseCurrency();
        return repository.update(userId, portfolioId, effectiveName, effectiveCurrency);
    }

    public void delete(long userId, long portfolioId) {
        getOrThrow(userId, portfolioId);
        repository.deleteByIdAndUser(userId, portfolioId);
    }

    public Portfolio lockForUpdate(long userId, long portfolioId) {
        return repository.lockForUpdate(userId, portfolioId)
                .orElseThrow(() -> new NotFoundException("portfolio", portfolioId));
    }
}
