package com.eternallite.app

import android.app.Application
import android.content.Context
import com.eternallite.app.crypto.AckOpener
import com.eternallite.app.crypto.B64
import com.eternallite.app.crypto.DeviceKeyStore
import com.eternallite.app.crypto.LazySodiumOps
import com.eternallite.app.crypto.OrderSealer
import com.eternallite.app.crypto.SignatureVerifier
import com.eternallite.app.crypto.Sodium
import com.eternallite.app.data.Address
import com.eternallite.app.data.AppDatabase
import com.eternallite.app.data.CartRepository
import com.eternallite.app.data.CatalogRepository
import com.eternallite.app.data.OrderRepository
import com.eternallite.app.data.Settings
import com.eternallite.app.mesh.Protocol
import com.eternallite.app.net.AckHandler
import com.eternallite.app.net.ApiClient
import com.eternallite.app.net.ConnectivityMonitor
import com.eternallite.app.net.EnrollRequest
import com.eternallite.app.net.GatewayUploader
import com.eternallite.app.net.NetworkException
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.SecureRandom

class EternalLiteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** Manual dependency wiring. One instance for the whole process. */
class AppContainer(context: Context) {
    val clock: () -> Long = { System.currentTimeMillis() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sodium: Sodium by lazy { LazySodiumOps(LazySodiumAndroid(SodiumAndroid())) }
    private val db = AppDatabase.open(context)
    private val api = ApiClient()

    val settings = Settings(db.settingDao())
    private val keyStore = DeviceKeyStore(settings, sodium)
    val catalog = CatalogRepository(db, api, settings, clock)
    val cart = CartRepository(db)
    val orders = OrderRepository(db, settings, keyStore, OrderSealer(sodium), clock)
    val connectivity = ConnectivityMonitor(context, settings.forceOffline, scope)

    private val ackHandler = AckHandler(db, settings, keyStore, SignatureVerifier(sodium), AckOpener(sodium), clock)
    val uploader = GatewayUploader(db, settings, api, connectivity, ackHandler, clock, scope)

    fun start() {
        uploader.start()

        // Refresh the full catalog whenever the node becomes online.
        scope.launch {
            connectivity.isOnline.collect { online ->
                if (online && settings.get(Settings.DEVICE_ID) != null) {
                    try {
                        catalog.refresh()
                    } catch (e: NetworkException) {
                        connectivity.reportBackendFailure()
                    } catch (e: Exception) {
                        // Wrong URL or a server error: keep the cached menu.
                    }
                }
            }
        }

        // Expiry timer, plus a safety-net upload round.
        scope.launch {
            while (true) {
                delay(Protocol.EXPIRY_SWEEP_MS)
                runCatching { orders.expirySweep() }
                uploader.kick()
            }
        }

        // Retry probe: after a backend network error the node is not online, so nothing else would call
        // the backend again. This poll does, and its first success puts the node back online.
        // (From M6 this same poll also carries STOCK packets.)
        scope.launch {
            while (true) {
                delay(Protocol.ONLINE_RETRY_MS)
                if (!connectivity.shouldProbe) continue
                val url = settings.get(Settings.SERVER_URL) ?: continue
                try {
                    api.stock(url)
                    connectivity.reportBackendOk()
                } catch (e: Exception) {
                    // still unreachable
                }
            }
        }
    }

    /**
     * First-time setup. Needs internet once: generates keys and node_id, enrolls, downloads the catalog.
     * Throws NetworkException when the server cannot be reached.
     */
    suspend fun enroll(serverUrl: String, name: String, phone: String, address: Address, role: String) {
        val url = serverUrl.trim().trimEnd('/')
        val nodeId = settings.get(Settings.NODE_ID) ?: newNodeId().also { settings.set(Settings.NODE_ID, it) }
        val keys = keyStore.getOrCreate()
        val response = api.enroll(
            url,
            EnrollRequest(name.trim(), phone.trim(), address, nodeId, role, B64.encode(keys.signPk), B64.encode(keys.boxPk)),
        )
        settings.set(Settings.SERVER_URL, url)
        settings.set(Settings.DEVICE_ID, response.deviceId)
        settings.set(Settings.SERVER_SIGN_PK, response.serverSignPk)
        settings.set(Settings.SERVER_BOX_PK, response.serverBoxPk)
        settings.set(Settings.NAME, name.trim())
        settings.set(Settings.PHONE, phone.trim())
        settings.set(Settings.ROLE, role)
        settings.set(Settings.ADDRESS_JSON, Protocol.json.encodeToString(Address.serializer(), address))
        catalog.refresh()
        connectivity.reportBackendOk()
        settings.set(Settings.ENROLLED, "1")
    }

    suspend fun isEnrolled(): Boolean = settings.enrolled.first()

    /** 8 random lowercase hex characters, generated once per install. */
    private fun newNodeId(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
