package dev.josu.hypecar.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerSwipeDecisionTest {
    @Test
    fun `positive drag over threshold goes to previous`() {
        assertThat(PlayerSwipeDecision.fromOffset(140f, 120f)).isEqualTo(PlayerSwipeDirection.PREVIOUS)
    }

    @Test
    fun `negative drag over threshold goes to next`() {
        assertThat(PlayerSwipeDecision.fromOffset(-140f, 120f)).isEqualTo(PlayerSwipeDirection.NEXT)
    }

    @Test
    fun `short drag stays on current track`() {
        assertThat(PlayerSwipeDecision.fromOffset(40f, 120f)).isNull()
        assertThat(PlayerSwipeDecision.fromOffset(-40f, 120f)).isNull()
    }
}
