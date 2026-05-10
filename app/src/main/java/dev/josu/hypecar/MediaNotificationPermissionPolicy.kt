package dev.josu.hypecar

import android.content.pm.PackageManager
import android.os.Build

internal object MediaNotificationPermissionPolicy {
    fun shouldRequest(sdkInt: Int, permissionGrantState: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            permissionGrantState != PackageManager.PERMISSION_GRANTED
}
