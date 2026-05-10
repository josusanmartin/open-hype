package dev.josu.hypecar.core.data.di

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiBaseUrlSelectorTest {
    @Test
    fun releaseBuildUsesProductionUrlForAaosEmulator() {
        val selected = ApiBaseUrlSelector.select(
            isAutomotive = true,
            isEmulator = true,
            isDevProxyEnabled = false,
        )

        assertThat(selected).isEqualTo("https://api.hypem.com/v2/")
    }

    @Test
    fun debugBuildCanUseLocalProxyForAaosEmulator() {
        val selected = ApiBaseUrlSelector.select(
            isAutomotive = true,
            isEmulator = true,
            isDevProxyEnabled = true,
        )

        assertThat(selected).isEqualTo("http://10.0.2.2:8787/v2/")
    }
}
