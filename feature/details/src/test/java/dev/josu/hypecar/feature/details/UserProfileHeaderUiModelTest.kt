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
        assertThat(model.stats).containsExactly("123 favorites", "456 followers", "78 following").inOrder()
        assertThat(model.summaryLine).isEqualTo("123 favorites · 456 followers · 78 following")
    }
}
