package com.protify.portfolio.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One case per row in day-1-dev-C.md's handler table, plus the deliberate-NPE assertion that
 * the 500 handler leaks nothing. Exercised through a small test-only controller
 * ({@link ThrowingTestController}) so every exception is thrown from a real dispatched
 * request, exactly as it happens in production.
 */
@WebMvcTest(controllers = ThrowingTestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void domainExceptionMapsToItsOwnStatusAndProblemType() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void beanValidationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/test/boom/method-argument-not-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void constraintViolationOnPathVariableReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom/constraint-violation/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void malformedJsonBodyReturns400AndDoesNotEchoTheRawBody() throws Exception {
        mockMvc.perform(post("/api/v1/test/boom/not-readable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/malformed-request"))
                .andExpect(jsonPath("$.detail").value("The request body could not be read."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not valid json"))));
    }

    @Test
    void typeMismatchOnQueryParamReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom/type-mismatch").param("count", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-parameter"));
    }

    @Test
    void missingRequiredQueryParamReturns400WithFieldError() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom/missing-parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("query"));
    }

    @Test
    void authenticationExceptionReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom/authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"));
    }

    @Test
    void accessDeniedExceptionReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/forbidden"));
    }

    @Test
    void unhandledExceptionReturns500WithNoStackTraceNoSqlNoClassName() throws Exception {
        String body = mockMvc.perform(get("/api/v1/test/boom/null-pointer"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/internal"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("com.protify");
        assertThat(body).doesNotContain("at ");
        assertThat(body).doesNotContainIgnoringCase("select ");
        assertThat(body).doesNotContainIgnoringCase("SQL");
    }
}
