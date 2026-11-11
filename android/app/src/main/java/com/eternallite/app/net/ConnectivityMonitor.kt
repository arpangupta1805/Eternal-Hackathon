package com.eternallite.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * "Online" as defined in docs/03-protocol.md: the system reports a validated internet connection,
 * debug force-offline is off, and the last backend call did not fail with a network error.
 */
class ConnectivityMonitor(
    context: Context,
    forceOffline: Flow<Boolean>,
    scope: CoroutineScope,
) {
    private val systemValidated = MutableStateFlow(false)
    private val backendFailed = MutableStateFlow(false)
    private val forced = forceOffline.stateIn(scope, SharingStarted.Eagerly, false)

    val isOnline: StateFlow<Boolean> =
        combine(systemValidated, backendFailed, forced) { validated, failed, force -> validated && !failed && !force }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** True when a retry probe makes sense: the system has internet and force-offline is off, but the backend failed. */
    val shouldProbe: Boolean get() = systemValidated.value && !forced.value && backendFailed.value

    /** A backend call failed with a network error: not online until the next success or connectivity change. */
    fun reportBackendFailure() {
        backendFailed.value = true
    }

    fun reportBackendOk() {
        backendFailed.value = false
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated != systemValidated.value) backendFailed.value = false // connectivity changed: try again
            systemValidated.value = validated
        }

        override fun onLost(network: Network) {
            systemValidated.value = false
            backendFailed.value = false
        }
    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(callback)
    }
}
