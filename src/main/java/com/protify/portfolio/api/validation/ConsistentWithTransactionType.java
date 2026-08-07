package com.protify.portfolio.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for the rules that span two fields of
 * {@link com.protify.portfolio.api.dto.CreateTransactionRequest} and therefore cannot be
 * expressed field-by-field (API_CONTRACT.md §8): {@code symbol} is required for
 * {@code BUY}/{@code SELL}/{@code DIVIDEND}/{@code FEE} and forbidden for
 * {@code DEPOSIT}/{@code WITHDRAWAL}; {@code quantity} is required for
 * {@code BUY}/{@code SELL}/{@code DIVIDEND} and forbidden for the three cash types.
 *
 * <p>Deliberately a constraint rather than an {@code if} in the controller: a controller
 * {@code if} would produce a hand-rolled error body, while this fails through the same
 * {@code MethodArgumentNotValidException} path as every other Bean Validation failure and so
 * lands in {@code GlobalExceptionHandler}'s {@code errors[]} array with a real field name — the
 * array the add-transaction form binds to its inputs (day-3-dev-C.md D3-C4).
 */
@Documented
@Constraint(validatedBy = ConsistentWithTransactionTypeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConsistentWithTransactionType {

    String message() default "is inconsistent with the transaction type";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
