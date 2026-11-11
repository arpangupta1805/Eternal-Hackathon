package com.eternallite.app.data

import androidx.room.withTransaction
import com.eternallite.app.crypto.B64
import com.eternallite.app.crypto.DeviceKeyStore
import com.eternallite.app.crypto.OrderSealer
import com.eternallite.app.mesh.Protocol
import com.eternallite.app.net.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Typed access to the settings table. */
class Settings(private val dao: SettingDao) {
    suspend fun get(key: String): String? = dao.get(key)
    fun flow(key: String): Flow<String?> = dao.flow(key)
    suspend fun set(key: String, value: String) = dao.set(SettingEntity(key, value))
    suspend fun remove(key: String) = dao.remove(key)

    /** True once enrollment has finished and the first catalog is cached. */
    val enrolled: Flow<Boolean> = dao.flow(ENROLLED).map { it == "1" }
    val forceOffline: Flow<Boolean> = dao.flow(FORCE_OFFLINE).map { it == "1" }

    suspend fun address(): Address? =
        get(ADDRESS_JSON)?.let { Protocol.json.decodeFromString(Address.serializer(), it) }

    companion object {
        const val NODE_ID = "node_id"
        const val DEVICE_ID = "device_id"
        const val ROLE = "role"
        const val SERVER_URL = "server_url"
        const val SERVER_SIGN_PK = "server_sign_pk"
        const val SERVER_BOX_PK = "server_box_pk"
        const val NAME = "name"
        const val PHONE = "phone"
        const val ADDRESS_JSON = "address_json"
        const val DEVICE_KEYS = "device_keys"
        const val ENROLLED = "enrolled"
        const val FORCE_OFFLINE = "force_offline"
    }
}

class CatalogRepository(
    private val db: AppDatabase,
    private val api: ApiClient,
    private val settings: Settings,
    private val clock: () -> Long,
) {
    private val dao = db.catalogDao()

    val stores: Flow<List<StoreEntity>> = dao.stores()
    fun store(storeId: String): Flow<StoreEntity?> = dao.store(storeId)
    fun items(storeId: String): Flow<List<ItemEntity>> = dao.items(storeId)

    /** When the menu was last known to be current: the catalog sync or the newest stock snapshot. */
    val menuAsOf: Flow<Long?> = dao.latestStockUpdate()

    /** Downloads the full catalog and replaces the cache. Throws NetworkException / HttpException. */
    suspend fun refresh() {
        val url = settings.get(Settings.SERVER_URL) ?: return
        val catalog = api.catalog(url)
        val now = clock()
        val stores = catalog.stores.mapIndexed { index, s ->
            StoreEntity(s.storeId, s.name, s.kind, s.open, s.deliveryFee, s.etaMinutes, s.stockSeq, now, index)
        }
        var position = 0
        val items = catalog.stores.flatMap { s ->
            s.items.map { ItemEntity(it.itemId, s.storeId, it.name, it.category, it.price, it.inStock, position++) }
        }
        dao.replaceCatalog(stores, items, CatalogMetaEntity(catalogVersion = catalog.catalogVersion, syncedAt = now))
    }
}

class CartRepository(private val db: AppDatabase) {
    private val dao = db.cartDao()

    val rows: Flow<List<CartRow>> = dao.rows()

    /** The store the cart belongs to, or null when the cart is empty. */
    suspend fun storeId(): String? = dao.storeId()

    /** Changes the quantity by delta. A cart holds one store; adding from another store replaces it. */
    suspend fun change(item: ItemEntity, delta: Int) = db.withTransaction {
        val cartStore = dao.storeId()
        if (cartStore != null && cartStore != item.storeId) dao.clear()
        val current = dao.get(item.itemId)
        val qty = (current?.qty ?: 0) + delta
        if (qty <= 0) {
            dao.remove(item.itemId)
        } else {
            dao.upsert(current?.copy(qty = qty) ?: CartItemEntity(item.itemId, item.storeId, qty, OnUnavailable.DROP, null))
        }
    }

    suspend fun setRule(itemId: String, onUnavailable: String, substituteItemId: String?) {
        val current = dao.get(itemId) ?: return
        dao.upsert(current.copy(onUnavailable = onUnavailable, substituteItemId = substituteItemId))
    }

    suspend fun clear() = dao.clear()
}

class OrderRepository(
    private val db: AppDatabase,
    private val settings: Settings,
    private val keyStore: DeviceKeyStore,
    private val sealer: OrderSealer,
    private val clock: () -> Long,
) {
    private val dao = db.orderDao()

    val orders: Flow<List<OrderEntity>> = dao.all()
    fun order(orderId: String): Flow<OrderEntity?> = dao.order(orderId)

    /**
     * The single path for placing an order, online or offline:
     * build the body, sign, seal, wrap, store in `orders` and `packets`, empty the cart.
     * Returns the new order_id. The caller then kicks the uploader.
     */
    suspend fun placeOrder(tolerancePaise: Int): String {
        val rows = db.cartDao().getRows()
        check(rows.isNotEmpty()) { "The cart is empty." }
        val store = db.catalogDao().getStore(rows.first().storeId) ?: error("This store is no longer in the menu.")
        val meta = db.catalogDao().getMeta() ?: error("The menu has not been downloaded yet.")
        val deviceId = settings.get(Settings.DEVICE_ID) ?: error("This phone is not set up yet.")
        val nodeId = settings.get(Settings.NODE_ID) ?: error("This phone is not set up yet.")
        val serverBoxPk = settings.get(Settings.SERVER_BOX_PK)?.let(B64::decode) ?: error("This phone is not set up yet.")
        val address = settings.address() ?: error("No delivery address saved.")
        val phone = settings.get(Settings.PHONE).orEmpty()
        val keys = keyStore.getOrCreate()
        val now = clock()

        val body = OrderBuilder.build(
            orderId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            now = now,
            catalogVersion = meta.catalogVersion,
            storeId = store.storeId,
            deliveryFee = store.deliveryFee,
            lines = rows.map { CartLine(it.itemId, it.qty, it.price, it.onUnavailable, it.substituteItemId) },
            tolerancePaise = tolerancePaise,
            address = address,
            phone = phone,
        )
        val (packet, bodyJson) = sealer.seal(body, keys.signSk, serverBoxPk, nodeId)
        val envelopeJson = packet.toJson()
        check(envelopeJson.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_PACKET_BYTES) { "This order is too large to relay. Remove some items." }

        db.withTransaction {
            dao.insert(
                OrderEntity(
                    orderId = body.orderId,
                    storeId = store.storeId,
                    storeName = store.name,
                    bodyJson = bodyJson,
                    envelopeJson = envelopeJson,
                    state = OrderState.QUEUED,
                    ackResultJson = null,
                    createdAt = now,
                    expiresAt = body.expiresAt,
                    updatedAt = now,
                    handedTo = "",
                    uploadRejected = false,
                )
            )
            db.packetDao().upsert(
                PacketEntity(
                    key = packet.key,
                    type = packet.type,
                    id = packet.id,
                    envelopeJson = envelopeJson,
                    expiresAt = packet.expiresAt,
                    hopsLeft = packet.hopsLeft,
                    receivedAt = now,
                    isOwn = true,
                    uploadRejected = false,
                )
            )
            db.cartDao().clear()
        }
        return body.orderId
    }

    /**
     * Run every 30 seconds: drops expired packets and marks the user's own unanswered
     * orders UNCONFIRMED once their 20 minutes are over.
     */
    suspend fun expirySweep() {
        val now = clock()
        db.packetDao().deleteExpired(now)
        dao.markExpiredUnconfirmed(now)
    }
}
