package com.vythera.relay.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vythera.relay.R
import com.vythera.relay.relay
import com.vythera.relay.transfer.TransferDirection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Relay reachable: runs the node, holds the Wi-Fi multicast lock discovery needs,
 * and nudges discovery whenever the network changes.
 *
 * Declared as a `connectedDevice` foreground service: it exists to keep the user's own
 * devices connected. Its notification is on a minimum-importance channel so it sits
 * collapsed at the bottom of the shade, and becomes a progress notification while
 * something is transferring.
 */
class RelayService : LifecycleService() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = relay.notifications
        ServiceCompat.startForeground(
            this,
            RelayNotifications.ID_STATUS,
            notifications.status(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        multicastLock = getSystemService(WifiManager::class.java)?.createMulticastLock("relay-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        lifecycleScope.launch {
            val controller = relay.controller()
            controller.startNetworking()
            watchNetwork { controller.refresh() }
            controller.transfers
                .map { list -> list.firstOrNull { it.status.isActive } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.progress?.fraction?.times(100)?.toInt() == new?.progress?.fraction?.times(100)?.toInt() && old?.status == new?.status }
                .collect { active ->
                    val notification = if (active == null) {
                        notifications.status()
                    } else {
                        val peer = controller.deviceName(active.peerId)
                        notifications.status(
                            title = getString(
                                if (active.direction == TransferDirection.OUTGOING) R.string.notification_sending else R.string.notification_receiving,
                                peer,
                            ),
                            text = active.items.singleOrNull()?.name ?: resources.getQuantityString(R.plurals.offer_files, active.items.size, active.items.size),
                            progress = (active.progress.fraction * 100).toInt(),
                        )
                    }
                    notifications.update(RelayNotifications.ID_STATUS, notification)
                }
        }
    }

    private fun watchNetwork(onChange: () -> Unit) {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = onChange()
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback,
        )
        networkCallback = callback
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        multicastLock?.takeIf { it.isHeld }?.release()
        relay.controllerState.value?.stopNetworking()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
