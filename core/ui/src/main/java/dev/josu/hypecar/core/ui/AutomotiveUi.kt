package dev.josu.hypecar.core.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/** Single device classifier shared by the app shell and every feature. */
fun Context.isAutomotiveUi(): Boolean {
    val uiMode = resources.configuration.uiMode
    return packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
        (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR ||
        Build.PRODUCT.contains("gcar", ignoreCase = true) ||
        Build.FINGERPRINT.contains("gcar", ignoreCase = true)
}
