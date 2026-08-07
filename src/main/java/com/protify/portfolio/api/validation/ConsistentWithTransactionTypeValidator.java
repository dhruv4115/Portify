package com.protify.portfolio.api.validation;

import com.protify.portfolio.api.dto.CreateTransactionRequest;
import com.protify.portfolio.common.enums.TransactionType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.EnumSet;
import java.util.Set;

/**
 * The four cross-field rules of API_CONTRACT.md §8. Every violation is reported against a
 * <em>property node</em> ({@code symbol} or {@code quantity}) rather than the default
 * class-level node: Spring's adapter turns a violation with a property path into a
 * {@code FieldError}, and a class-level violation into a global one, which
 * {@code GlobalExceptionHandler} (reading {@code getFieldErrors()}) would drop — the response
 * would then be a 400 with an empty {@code errors[]} and nothing for the form to highlight.
 *
 * <p>Mirrors the same rule sets {@code TransactionService} enforces server-side. The duplication
 * is intentional: this layer exists so a malformed request fails as a 400 with field errors
 * before any DB transaction opens, while the service's copy keeps the rule true for callers that
 * never pass through a controller.
 */
public class ConsistentWithTransactionTypeValidator
        implements ConstraintValidator<ConsistentWithTransactionType, CreateTransactionRequest> {

    private static final Set<TransactionType> REQUIRES_SYMBOL = EnumSet.of(
            TransactionType.BUY, TransactionType.SELL, TransactionType.DIVIDEND, TransactionType.FEE);
    private static final Set<TransactionType> FORBIDS_SYMBOL = EnumSet.of(
            TransactionType.DEPOSIT, TransactionType.WITHDRAWAL);
    private static final Set<TransactionType> REQUIRES_QUANTITY = EnumSet.of(
            TransactionType.BUY, TransactionType.SELL, TransactionType.DIVIDEND);

    @Override
    public boolean isValid(CreateTransactionRequest request, ConstraintValidatorContext context) {
        if (request == null || request.type() == null) {
            // @NotNull on `type` owns that failure; reporting it twice would put two entries in
            // errors[] for one mistake.
            return true;
        }
        context.disableDefaultConstraintViolation();

        TransactionType type = request.type();
        boolean hasSymbol = request.symbol() != null && !request.symbol().isBlank();
        boolean valid = true;

        if (REQUIRES_SYMBOL.contains(type) && !hasSymbol) {
            valid = reject(context, "symbol", "is required for a " + type + " transaction");
        }
        if (FORBIDS_SYMBOL.contains(type) && hasSymbol) {
            valid = reject(context, "symbol", "must not be supplied for a " + type + " transaction");
        }
        if (REQUIRES_QUANTITY.contains(type) && request.quantity() == null) {
            valid = reject(context, "quantity", "is required for a " + type + " transaction");
        }
        if (!REQUIRES_QUANTITY.contains(type) && request.quantity() != null) {
            valid = reject(context, "quantity", "must not be supplied for a " + type + " transaction");
        }
        return valid;
    }

    /** Always returns {@code false} so a caller can write {@code valid = reject(...)} and keep
     * collecting the remaining violations rather than returning on the first one. */
    private static boolean reject(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
        return false;
    }
}
