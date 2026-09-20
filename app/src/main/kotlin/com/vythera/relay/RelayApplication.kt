package com.vythera.relay

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.vythera.relay.service.RelayNotifications
import com.vythera.relay.data.db.RelayDatabase
import com.vythera.relay.data.db.RoomTrustStore
import com.vythera.relay.data.security.KeystoreIdentityStore
import com.vythera.relay.data.settings.SettingsRepository
import com.vythera.relay.data.storage.MediaStoreStorage
import com.vythera.relay.data.storage.SwitchableStorage
import com.vythera.relay.node.NodeConfig
import com.vythera.relay.node.RelayNode
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.transfer.DirectoryStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RelayApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
    }
}

/**
 * Hand-written dependency graph. Relay has few long-lived objects, and all of them are
 * here; a DI framework would add build time and indirection without removing any code.
 *
 * The [RelayController] needs the identity (a Keystore unwrap, or key generation on first
 * run) and the trusted devices (a database read), so it is built off the main thread;
 * the splash screen stays up until [controllerState] has a value.
 */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: RelayDatabase by lazy { RelayDatabase.create(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val notifications: RelayNotifications by lazy { RelayNotifications(app) }

    private val _controllerState = MutableStateFlow<RelayController?>(null)
    val controllerState: StateFlow<RelayController?> = _controllerState.asStateFlow()

    private val controllerDeferred: Deferred<RelayController> = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        val identity = KeystoreIdentityStore(app).loadOrCreate()
        val trustStore = RoomTrustStore.load(database.trustedDevices())
        val current = settings.current()
        val storage = SwitchableStorage(MediaStoreStorage(app))
        val node = RelayNode(
            config = NodeConfig(
                deviceName = current.deviceName.ifBlank { defaultDeviceName() },
                deviceType = if (app.resources.configuration.smallestScreenWidthDp >= 600) DeviceType.TABLET else DeviceType.PHONE,
                platform = Platform.ANDROID,
                appVersion = BuildConfig.VERSION_NAME,
            ),
            identity = identity,
            trustStore = trustStore,
            storage = storage,
            parentScope = scope,
            clipboardStorage = DirectoryStorage(File(app.cacheDir, "clipboard")),
            log = { Log.i("Relay", it) },
        )
        RelayController(app, scope, node, storage, database, settings, notifications, current).also { _controllerState.value = it }
    }

    suspend fun controller(): RelayController = controllerDeferred.await()

    fun warmUp() {
        controllerDeferred.start()
    }

    /** "Shubham's Pixel" if the user named the phone in system settings, otherwise the model. */
    fun defaultDeviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Android" }
}

val android.content.Context.relay: AppContainer get() = (applicationContext as RelayApplication).container
