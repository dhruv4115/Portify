package com.protify.portfolio.api.error;

import com.protify.portfolio.common.error.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only controller for {@link GlobalExceptionHandlerTest}; each method throws exactly one
 * of the eight exceptions {@code GlobalExceptionHandler} handles. */
@RestController
@Validated
public class ThrowingTestController {

    @GetMapping("/test/boom/not-found")
    public String notFound() {
        throw new NotFoundException("portfolio", 7);
    }

    @PostMapping("/test/boom/method-argument-not-valid")
    public String methodArgumentNotValid(@RequestBody @Valid SellRequest body) {
        return "unreachable";
    }

    @GetMapping("/test/boom/constraint-violation/{quantity}")
    public String constraintViolation(@PathVariable @Min(1) int quantity) {
        return "unreachable";
    }

    @PostMapping("/test/boom/not-readable")
    public String notReadable(@RequestBody SellRequest body) {
        return "unreachable";
    }

    @GetMapping("/test/boom/type-mismatch")
    public String typeMismatch(@RequestParam int count) {
        return "unreachable";
    }

    @GetMapping("/test/boom/missing-parameter")
    public String missingParameter(@RequestParam String query) {
        return "unreachable";
    }

    @GetMapping("/test/boom/authentication")
    public String authentication() {
        throw new BadCredentialsException("bad token");
    }

    @GetMapping("/test/boom/access-denied")
    public String accessDenied() {
        throw new AccessDeniedException("not allowed");
    }

    @GetMapping("/test/boom/null-pointer")
    public String nullPointer() {
        String value = null;
        return value.trim();
    }

    public record SellRequest(@Min(1) Integer quantity) {
    }
}
