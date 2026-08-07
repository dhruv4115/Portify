package com.protify.portfolio.api.user;

import com.protify.portfolio.api.dto.PreferencesDto;
import com.protify.portfolio.api.dto.UpdatePreferencesRequest;
import com.protify.portfolio.api.dto.UserResponse;
import com.protify.portfolio.api.mapper.UserMapper;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.user.UserPreferences;
import com.protify.portfolio.user.UserPreferencesService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first real vertical slice (day-1-dev-C.md D1-C3): security filter -> resolver ->
 * controller -> mapper -> DTO. No business logic here, only delegation and mapping —
 * persistence types never cross this boundary, which is why these methods return
 * {@link UserResponse}/{@link PreferencesDto} and never touch whatever
 * {@link CurrentUserResolver}'s real implementation looks up internally.
 *
 * <p>Preferences hang off {@code /me} rather than living at a top-level path because they are
 * account state, not a resource of their own: there is exactly one set per user and it is
 * reached only as "mine". No id ever appears in these paths, so there is no id to get wrong
 * and no way to address someone else's.
 */
@RestController
public class MeController {

    private final CurrentUserResolver currentUserResolver;
    private final UserPreferencesService userPreferencesService;
    private final UserMapper userMapper;

    public MeController(
            CurrentUserResolver currentUserResolver,
            UserPreferencesService userPreferencesService,
            UserMapper userMapper) {
        this.currentUserResolver = currentUserResolver;
        this.userPreferencesService = userPreferencesService;
        this.userMapper = userMapper;
    }

    @GetMapping("/me")
    public UserResponse me() {
        CurrentUser currentUser = currentUserResolver.resolve();
        return userMapper.toResponse(currentUser, userPreferencesService.get(currentUser.id()));
    }

    /** Also served on its own so a client that only wants to re-sync preferences need not
     * re-read the whole identity. */
    @GetMapping("/me/preferences")
    public PreferencesDto preferences() {
        long userId = currentUserResolver.resolve().id();
        return userMapper.toDto(userPreferencesService.get(userId));
    }

    /**
     * Partial by definition: an omitted field keeps its stored value. Sending {@code {}} is
     * rejected rather than treated as a successful no-op — the same rule, and the same
     * reasoning, as {@code PATCH /portfolios/{id}}: a request that cannot change anything is
     * far more likely to be a client bug than an intention.
     *
     * @return the preferences as they stand after the write, so the client renders what was
     *         actually stored rather than what it sent
     */
    @PatchMapping("/me/preferences")
    public PreferencesDto updatePreferences(@Valid @RequestBody UpdatePreferencesRequest request) {
        if (request.isEmpty()) {
            throw new ValidationException(
                    "preferences-update-empty",
                    "At least one of theme, language, density or motion must be provided.");
        }

        long userId = currentUserResolver.resolve().id();
        UserPreferences updated = userPreferencesService.update(userId, new UserPreferences(
                request.theme(), request.language(), request.density(), request.motion()));
        return userMapper.toDto(updated);
    }
}
