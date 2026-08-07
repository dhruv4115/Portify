package com.protify.portfolio.api.error;

import com.protify.portfolio.common.error.CurrencyMismatchException;
import com.protify.portfolio.common.error.DomainException;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.error.UpstreamException;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.common.money.MoneyUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place every error in the product flows through — one {@code @RestControllerAdvice},
 * and only one. Every response is RFC 9457 {@link ProblemDetail}, {@code
 * application/problem+json}, one shape everywhere (API_CONTRACT.md §0.7).
 *
 * <p><b>Non-negotiable:</b> no response body built here may ever contain a stack trace, a SQL
 * fragment, or a {@code com.protify} class name. The 500 handler logs the full exception with
 * the correlation ID and returns a fixed generic message and nothing more — the correlation ID
 * is how support finds the real error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Appended to a {@code problemType()}/slug to build the RFC 9457 {@code type} URI. */
    private static final URI PROBLEM_BASE_URI = URI.create("https://portfolio.local");

    /** Agreed with Dev B (day-1-dev-B.md D1-B4): the MDC key their filter populates. */
    private static final String MDC_CORRELATION_ID_KEY = "correlationId";

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomainException(DomainException ex, HttpServletRequest request) {
        return problemDetail(ex.status(), titleFor(ex), ex.getMessage(), ex.problemType(), request, null);
    }

    /**
     * {@link ConflictException} is Dev C's own 409 seam (api/-scoped, not part of {@code
     * common}'s sealed hierarchy) — see its Javadoc for why it exists separately from
     * {@link DomainException}.
     */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), ex.problemTypeSlug(), request, null);
    }

    /**
     * API_CONTRACT.md §18's "501, never a 404, so the frontend can tell 'turned off' from
     * 'wrong URL'". See {@link FeatureDisabledException}.
     */
    @ExceptionHandler(FeatureDisabledException.class)
    public ProblemDetail handleFeatureDisabled(FeatureDisabledException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_IMPLEMENTED, "Not implemented", ex.getMessage(),
                ex.problemTypeSlug(), request, null);
    }

    /** Dev C's 400-with-field-names seam — see {@link RequestValidationException} for why the
     * {@code common} hierarchy cannot express this. */
    @ExceptionHandler(RequestValidationException.class)
    public ProblemDetail handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage(),
                ex.problemTypeSlug(), request, ex.violations());
    }

    /**
     * A 422 that the add-transaction form has to be able to point at an input, so it carries the
     * {@code errors[]} entry API_CONTRACT.md §8 specifies rather than the bare body the generic
     * {@link #handleDomainException} would produce. Spring dispatches to this more specific
     * handler in preference to that one.
     */
    @ExceptionHandler(InsufficientQuantityException.class)
    public ProblemDetail handleInsufficientQuantity(InsufficientQuantityException ex, HttpServletRequest request) {
        List<FieldViolation> violations = List.of(new FieldViolation("quantity",
                "must not exceed holding of %s".formatted(MoneyUtils.quantity(ex.held()).toPlainString())));
        return problemDetail(ex.status(), titleFor(ex), ex.getMessage(), ex.problemType(), request, violations);
    }

    /** Same reasoning as {@link #handleInsufficientQuantity}, against the {@code currency} field
     * (API_CONTRACT.md §8). */
    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex, HttpServletRequest request) {
        List<FieldViolation> violations = List.of(
                new FieldViolation("currency", "must be " + ex.expected()));
        return problemDetail(ex.status(), titleFor(ex), ex.getMessage(), ex.problemType(), request, violations);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return problemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
                "The request failed validation.", "/errors/validation-failed", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(lastPathNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return problemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
                "The request failed validation.", "/errors/validation-failed", request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Deliberately generic: ex.getMessage() can quote fragments of the raw body Jackson
        // failed to parse, and a raw body must never reach the response.
        return problemDetail(HttpStatus.BAD_REQUEST, "Malformed request body",
                "The request body could not be read.", "/errors/malformed-request", request, null);
    }

    /**
     * The container's own upload limit, tripped before any controller sees the request.
     * {@code TransactionController} enforces a smaller cap of its own with a message naming the
     * {@code file} part; this is the backstop for an upload so large that Tomcat refuses it first,
     * and it exists only so that case is a 400 rather than falling through to a 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Upload too large",
                "The uploaded file is too large.", "/errors/upload-too-large", request,
                List.of(new FieldViolation("file", "is too large")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // The parameter name is ours (it's the controller's own method signature); the
        // caller-supplied value is deliberately not echoed back.
        String detail = "Parameter '%s' has an invalid value.".formatted(ex.getName());
        return problemDetail(HttpStatus.BAD_REQUEST, "Invalid parameter", detail,
                "/errors/invalid-parameter", request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        List<FieldViolation> violations = List.of(new FieldViolation(ex.getParameterName(), "must not be blank"));
        return problemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
                "A required query parameter is missing.", "/errors/validation-failed", request, violations);
    }

    /**
     * Spring throws this for any request path matching no controller and no static resource
     * (Boot 3.2+) — without this handler it would otherwise fall through to
     * {@link #handleUnexpected}, since it has no more specific match, and every unmapped path
     * (a typo'd URL, {@code GET /}) would wrongly answer 500 instead of 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_FOUND, "Not found",
                "No resource matches this path.", "/errors/not-found", request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.UNAUTHORIZED, "Unauthenticated",
                "Authentication is required to access this resource.", "/errors/unauthenticated", request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not permitted to perform this action.", "/errors/forbidden", request, null);
    }

    /**
     * The last-resort handler. Anything reaching here is a bug, not a domain rule, so the
     * caller gets nothing but a correlation ID — the full exception, stack trace included,
     * goes to the log and only the log.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = correlationId();
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);
        return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred.", "/errors/internal", request, null, correlationId);
    }

    /**
     * Exhaustive over {@link DomainException}'s sealed permits clause — the compiler rejects
     * this method if a subclass is ever added and not handled here, so there is no default
     * branch to forget.
     */
    private static String titleFor(DomainException ex) {
        return switch (ex) {
            case NotFoundException e -> "Not found";
            case ValidationException e -> "Validation failed";
            case InsufficientQuantityException e -> "Insufficient quantity";
            case CurrencyMismatchException e -> "Currency mismatch";
            case UpstreamException e -> "Upstream provider unavailable";
        };
    }

    private ProblemDetail problemDetail(HttpStatus status, String title, String detail, String problemTypeSlug,
            HttpServletRequest request, List<FieldViolation> errors) {
        return problemDetail(status, title, detail, problemTypeSlug, request, errors, correlationId());
    }

    private ProblemDetail problemDetail(HttpStatus status, String title, String detail, String problemTypeSlug,
            HttpServletRequest request, List<FieldViolation> errors, String correlationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(PROBLEM_BASE_URI.resolve(problemTypeSlug));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", correlationId);
        problem.setProperty("timestamp", Instant.now());
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    /**
     * Reads the correlation ID Dev B's {@code CorrelationIdFilter} puts in the MDC. Falls back
     * to a freshly generated one (without writing it back to the MDC — that filter owns the
     * MDC lifecycle, including clearing it) so every {@code ProblemDetail} still gets one even
     * on a request that reaches this handler before that filter is wired in.
     */
    private static String correlationId() {
        String existing = MDC.get(MDC_CORRELATION_ID_KEY);
        return (existing != null && !existing.isBlank()) ? existing : UUID.randomUUID().toString();
    }

    private static String lastPathNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot == -1 ? propertyPath : propertyPath.substring(lastDot + 1);
    }
}
