package com.protify.portfolio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.api.dto.UserResponse;
import com.protify.portfolio.api.user.CurrentUser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    void mapsEveryFieldOntoTheResponse() {
        Instant createdAt = Instant.parse("2026-07-31T08:02:11Z");
        CurrentUser currentUser = new CurrentUser(
                42L, "dhruv@example.com", "Dhruv Tiwari",
                "https://lh3.googleusercontent.com/a/ACg8oc", createdAt);

        UserResponse response = mapper.toResponse(currentUser);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("dhruv@example.com");
        assertThat(response.displayName()).isEqualTo("Dhruv Tiwari");
        assertThat(response.pictureUrl()).isEqualTo("https://lh3.googleusercontent.com/a/ACg8oc");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void mapsNullDisplayNameAndPictureUrlThrough() {
        CurrentUser currentUser = new CurrentUser(1L, "a@b.com", null, null, Instant.EPOCH);

        UserResponse response = mapper.toResponse(currentUser);

        assertThat(response.displayName()).isNull();
        assertThat(response.pictureUrl()).isNull();
    }
}
