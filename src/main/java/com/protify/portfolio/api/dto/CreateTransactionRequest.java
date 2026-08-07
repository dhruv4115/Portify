package com.protify.portfolio.api.dto;

import com.protify.portfolio.api.validation.ConsistentWithTransactionType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * API_CONTRACT.md §8. Amounts arrive as JSON strings and are bound to {@link BigDecimal} — never
 * a {@code double} — and the {@link Digits} bounds are the {@code DECIMAL(19,6)} /
 * {@code DECIMAL(19,4)} column shapes, so an over-precise value fails as a 400 rather than being
 * silently rounded on the way into the database.
 *
 * <p>{@code quantity} and {@code fees} are optional and mean "zero" when omitted, which is how
 * the contract's two rules for {@code quantity} — {@code @DecimalMin("0.000001")} <em>and</em>
 * "must be 0 for {@code DEPOSIT}/{@code WITHDRAWAL}/{@code FEE}" — are both honoured without
 * contradicting each other: a cash transaction leaves the field out, a {@code BUY} that sends
 * {@code "0"} is a 400. Which types must supply it is the class-level
 * {@link ConsistentWithTransactionType} rule, alongside the same rule for {@code symbol}.
 *
 * <p>For {@code DEPOSIT}/{@code WITHDRAWAL}, {@code price} is the cash amount (§8), which is why
 * it allows zero quantity to carry the whole value of the transaction.
 */
@ConsistentWithTransactionType
public record CreateTransactionRequest(
        @NotNull TransactionType type,
        @Size(max = 20) String symbol,
        @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal quantity,
        @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal price,
        @NotNull CurrencyCode currency,
        @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal fees,
        @NotNull @PastOrPresent Instant executedAt,
        @Size(max = 500) String note) {
}
