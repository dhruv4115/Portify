package com.protify.portfolio.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.UserMapper;
import com.protify.portfolio.user.UserPreferences;
import com.protify.portfolio.user.UserPreferencesService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code UserMapper} is a plain {@code @Component} with no dependencies, so it is imported for
 * real here rather than mocked; only the not-yet-real {@link CurrentUserResolver} seam is
 * mocked. The "no token -> 401" case from day-1-dev-C.md isn't testable yet: there is no
 * security filter chain in this codebase until Dev B's {@code SecurityConfig} lands (D1-B1) -
 * {@code GlobalExceptionHandlerTest} already covers the 401 shape once an
 * {@code AuthenticationException} is thrown.
 */
@WebMvcTest(controllers = MeController.class)
@Import(UserMapper.class)
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private UserPreferencesService userPreferencesService;

    @BeforeEach
    void storedPreferencesAreEmptyUnlessATestSaysOtherwise() {
        given(userPreferencesService.get(anyLong())).willReturn(UserPreferences.none());
    }

    @Test
    void happyPathReturnsTheProfileAsUserResponseNeverAppUser() throws Exception {
        given(currentUserResolver.resolve()).willReturn(new CurrentUser(
                42L, "dhruv@example.com", "Dhruv Tiwari",
                "https://lh3.googleusercontent.com/a/ACg8oc", Instant.parse("2026-07-31T08:02:11Z")));

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("dhruv@example.com"))
                .andExpect(jsonPath("$.displayName").value("Dhruv Tiwari"))
                .andExpect(jsonPath("$.pictureUrl").value("https://lh3.googleusercontent.com/a/ACg8oc"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-31T08:02:11Z"));
    }

    /** An account that has never saved a preference still gets the object, with null fields —
     * one shape for the client to read whether or not anything has been stored. */
    @Test
    void profileCarriesNullPreferencesWhenNoneHaveEverBeenSaved() throws Exception {
        given(currentUserResolver.resolve()).willReturn(
                new CurrentUser(42L, "dhruv@example.com", null, null, Instant.EPOCH));

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences").exists())
                .andExpect(jsonPath("$.preferences.theme").doesNotExist())
                .andExpect(jsonPath("$.preferences.language").doesNotExist());
    }

    @Test
    void profileCarriesStoredPreferencesSoTheClientNeedsNoSecondCallBeforeFirstPaint() throws Exception {
        given(currentUserResolver.resolve()).willReturn(
                new CurrentUser(42L, "dhruv@example.com", null, null, Instant.EPOCH));
        given(userPreferencesService.get(42L))
                .willReturn(new UserPreferences("dark", "hi", "compact", "reduced"));

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.preferences.language").value("hi"))
                .andExpect(jsonPath("$.preferences.density").value("compact"))
                .andExpect(jsonPath("$.preferences.motion").value("reduced"));
    }

    @Test
    void patchStoresOnlyTheFieldsSentAndEchoesBackWhatWasStored() throws Exception {
        given(currentUserResolver.resolve()).willReturn(
                new CurrentUser(42L, "dhruv@example.com", null, null, Instant.EPOCH));
        // The stored row already had a language; this PATCH names only the theme, so the
        // service is what preserves the rest — the response reflects the row, not the request.
        given(userPreferencesService.update(eq(42L), any()))
                .willReturn(new UserPreferences("dark", "hi", null, null));

        mockMvc.perform(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("dark"))
                .andExpect(jsonPath("$.language").value("hi"));

        ArgumentCaptor<UserPreferences> captor = ArgumentCaptor.forClass(UserPreferences.class);
        verify(userPreferencesService).update(eq(42L), captor.capture());
        assertThat(captor.getValue().theme()).isEqualTo("dark");
        assertThat(captor.getValue().language()).isNull();
    }

    /** A PATCH that names nothing cannot change anything, so it is a client bug rather than a
     * successful no-op — same rule as {@code PATCH /portfolios/{id}}. */
    @Test
    void emptyPatchBodyIsRejected() throws Exception {
        mockMvc.perform(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(userPreferencesService, never()).update(anyLong(), any());
    }

    @Test
    void unknownThemeIsRejectedRatherThanStoredForTheClientToChokeOn() throws Exception {
        mockMvc.perform(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"neon\"}"))
                .andExpect(status().isBadRequest());

        verify(userPreferencesService, never()).update(anyLong(), any());
    }
}
