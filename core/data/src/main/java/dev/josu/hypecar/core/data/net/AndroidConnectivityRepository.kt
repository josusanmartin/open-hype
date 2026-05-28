package dev.josu.hypecar.core.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.josu.hypecar.core.model.repository.Connectivity
import dev.josu.hypecar.core.model.repository.ConnectivityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-device implementation of [ConnectivityRepository] backed by Android's
 * [ConnectivityManager]. Registers a long-lived network callback at
 * construction time; the singleton scope (see DataModule) keeps it alive for
 * the application's lifetime.
 *
 * Three states are reported:
 *  - [Connectivity.Online] — a default network has both INTERNET capability
 *    and is VALIDATED (no captive portal).
 *  - [Connectivity.Limited] — INTERNET capability but not validated yet, or
 *    blocked (metered restriction etc.).
 *  - [Connectivity.Offline] — no default network at all.
 *
 * The current state is captured synchronously on construction so the first
 * value collectors see reflects the device's state at app launch, not the
 * first network callback after install.
 */
internal class AndroidConnectivityRepository(
    private val context: Context,
) : ConnectivityRepository {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _connectivity = MutableStateFlow(computeCurrent())
    override val connectivity: StateFlow<Connectivity> = _connectivity.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _connectivity.value = computeCurrent()
        }

        override fun onLost(network: Network) {
            _connectivity.value = computeCurrent()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _connectivity.value = computeCurrent()
        }
    }

    init {
        connectivityManager?.let { cm ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
    }

    private fun computeCurrent(): Connectivity {
        val cm = connectivityManager ?: return Connectivity.Offline
        val active = cm.activeNetwork ?: return Connectivity.Offline
        val capabilities = cm.getNetworkCapabilities(active) ?: return Connectivity.Offline
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return Connectivity.Offline
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) Connectivity.Online else Connectivity.Limited
    }
}
