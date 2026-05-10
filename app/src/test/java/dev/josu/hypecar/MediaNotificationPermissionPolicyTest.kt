package dev.josu.hypecar

import android.content.pm.PackageManager
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaNotificationPermissionPolicyTest {
    @Test
    fun `does not request notification permission before Android 13`() {
        val shouldRequest = MediaNotificationPermissionPolicy.shouldRequest(
            sdkInt = Build.VERSION_CODES.TIRAMISU - 1,
            permissionGrantState = PackageManager.PERMISSION_DENIED,
        )

        assertThat(shouldRequest).isFalse()
    }

    @Test
    fun `requests notification permission on Android 13 plus when denied`() {
        val shouldRequest = MediaNotificationPermissionPolicy.shouldRequest(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            permissionGrantState = PackageManager.PERMISSION_DENIED,
        )

        assertThat(shouldRequest).isTrue()
    }

    @Test
    fun `does not request notification permission when already granted`() {
        val shouldRequest = MediaNotificationPermissionPolicy.shouldRequest(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            permissionGrantState = PackageManager.PERMISSION_GRANTED,
        )

        assertThat(shouldRequest).isFalse()
    }
}
