package dev.josu.hypecar.feature.details

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.User
import org.junit.Test

class UserProfileHeaderUiModelTest {
    @Test
    fun `from maps user stats into header model`() {
        val model = UserProfileHeaderUiModel.from(
            User(
                username = "JSMDN",
                fullName = "JSMDN",
                favoritesCount = 123,
                followersCount = 456,
                followingCount = 78,
            ),
        )

        assertThat(model.title).isEqualTo("JSMDN")
        assertThat(model.handle).isEqualTo("@JSMDN")
        // Raw counts only — the composable formats them with
        // pluralStringResource so translations and pluralisation work.
        assertThat(model.favoritesCount).isEqualTo(123)
        assertThat(model.followersCount).isEqualTo(456)
        assertThat(model.followingCount).isEqualTo(78)
    }
}
