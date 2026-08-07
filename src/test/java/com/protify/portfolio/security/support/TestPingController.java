package com.protify.portfolio.security.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal protected endpoint used only by {@code SecurityConfigTest} /
 * {@code UnauthorisedAccessIT} to exercise {@code SecurityConfig} without depending on any real
 * controller (which would need {@code CurrentUserResolver}/{@code AppUserRepository}/a
 * DataSource). Mapped without a hardcoded {@code /api/v1} prefix — {@code WebConfig} adds it by
 * base-package predicate (this class is under {@code com.protify.portfolio}), same as every
 * real controller.
 */
@RestController
public class TestPingController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
