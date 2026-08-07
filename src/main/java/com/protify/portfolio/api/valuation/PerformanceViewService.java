package com.protify.portfolio.api.valuation;

import com.protify.portfolio.api.dto.PerformanceInterval;
import com.protify.portfolio.api.dto.PerformanceResponse;
import com.protify.portfolio.api.error.FieldViolation;
import com.protify.portfolio.api.error.RequestValidationException;
import com.protify.portfolio.api.mapper.PerformanceMapper;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.valuation.PerformancePoint;
import com.protify.portfolio.valuation.PerformanceResult;
import com.protify.portfolio.valuation.PerformanceService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The read model behind {@code /portfolios/{id}/performance} (day-3-dev-C.md D3-C3). Two jobs
 * Dev A's {@link PerformanceService} deliberately does not do:
 *
 * <ol>
 *   <li><b>Field-level range errors.</b> That service rejects {@code from > to} and an over-wide
 *       window with a {@code common.ValidationException}, which carries no field list, so the
 *       client gets a 400 with an empty {@code errors[]}. The same two rules are checked here
 *       first and thrown as a {@link RequestValidationException} naming {@code from}/{@code to},
 *       which §12 and D3-C3 both require. The bounds are kept identical to that service's on
 *       purpose — this is a better error for the same rule, never a different rule.</li>
 *   <li><b>Sampling.</b> {@link PerformanceService} computes {@code DAILY} only. Rather than
 *       accept an {@code interval} it silently ignores, or reject a value §12 documents, the
 *       weekly and monthly series are taken from the daily one here: sampling how densely to
 *       draw a line is a presentation concern, and every point returned is still a real computed
 *       valuation, not an average or an interpolation.</li>
 * </ol>
 */
@Service
public class PerformanceViewService {

    /** Matches {@code PerformanceService.MAX_RANGE_YEARS} — the two must not drift apart. */
    private static final long MAX_RANGE_YEARS = 5;

    private final PerformanceService performanceService;
    private final PerformanceMapper performanceMapper;

    public PerformanceViewService(PerformanceService performanceService, PerformanceMapper performanceMapper) {
        this.performanceService = performanceService;
        this.performanceMapper = performanceMapper;
    }

    public PerformanceResponse get(long userId, long portfolioId, LocalDate from, LocalDate to,
            PerformanceInterval interval, CurrencyCode currency) {
        requireValidRange(from, to);
        PerformanceResult result = performanceService.getPerformance(userId, portfolioId, from, to, currency);
        return performanceMapper.toResponse(result, sample(result.points(), interval), interval);
    }

    private static void requireValidRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new RequestValidationException("/errors/invalid-date-range",
                    "from must not be after to.",
                    List.of(new FieldViolation("from", "must not be after to")));
        }
        if (from.plusYears(MAX_RANGE_YEARS).isBefore(to)) {
            throw new RequestValidationException("/errors/invalid-date-range",
                    "The requested range must not exceed %d years.".formatted(MAX_RANGE_YEARS),
                    List.of(new FieldViolation("to", "must be within %d years of from".formatted(MAX_RANGE_YEARS))));
        }
    }

    /**
     * Keeps the last point of each interval — the closing value of that week or month, which is
     * what a chart of period-end values shows. Points arrive in ascending date order, so this is
     * one pass and no sorting.
     */
    private static List<PerformancePoint> sample(List<PerformancePoint> points, PerformanceInterval interval) {
        if (interval == PerformanceInterval.DAILY || points.size() < 2) {
            return points;
        }
        List<PerformancePoint> sampled = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            boolean lastOfBucket = i == points.size() - 1
                    || !bucketOf(points.get(i).date(), interval).equals(bucketOf(points.get(i + 1).date(), interval));
            if (lastOfBucket) {
                sampled.add(points.get(i));
            }
        }
        return sampled;
    }

    private static String bucketOf(LocalDate date, PerformanceInterval interval) {
        return switch (interval) {
            case DAILY -> date.toString();
            // ISO weeks, so a week spanning a year boundary stays one bucket
            case WEEKLY -> date.get(WeekFields.ISO.weekBasedYear()) + "-W"
                    + date.get(WeekFields.ISO.weekOfWeekBasedYear());
            case MONTHLY -> YearMonth.from(date).toString();
        };
    }
}
