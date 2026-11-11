package com.eternallite.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// Local storage, as listed in docs/05-android-app.md, "Local storage (Room)".

@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey @ColumnInfo(name = "store_id") val storeId: String,
    val name: String,
    val kind: String,
    val open: Boolean,
    @ColumnInfo(name = "delivery_fee") val deliveryFee: Int,
    @ColumnInfo(name = "eta_minutes") val etaMinutes: Int,
    @ColumnInfo(name = "stock_seq") val stockSeq: Int,
    /** issued_at of the newest stock snapshot applied, or the catalog sync time. */
    @ColumnInfo(name = "stock_updated_at") val stockUpdatedAt: Long,
    val position: Int,
)

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "store_id") val storeId: String,
    val name: String,
    val category: String,
    val price: Int,
    @ColumnInfo(name = "in_stock") val inStock: Boolean,
    val position: Int,
)

/** Single row (id = 0). */
@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "catalog_version") val catalogVersion: Int,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)

@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "store_id") val storeId: String,
    val qty: Int,
    @ColumnInfo(name = "on_unavailable") val onUnavailable: String,
    @ColumnInfo(name = "substitute_item_id") val substituteItemId: String?,
)

/** The user's own orders. Keeps the sealed envelope so an unconfirmed order can be re-uploaded later. */
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "store_id") val storeId: String,
    @ColumnInfo(name = "store_name") val storeName: String,
    @ColumnInfo(name = "body_json") val bodyJson: String,
    @ColumnInfo(name = "envelope_json") val envelopeJson: String,
    val state: String,
    @ColumnInfo(name = "ack_result_json") val ackResultJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Comma-separated node_ids of peers the order was handed to. */
    @ColumnInfo(name = "handed_to") val handedTo: String,
    /** The server answered an upload of this order with no ACK (it does not know this device). */
    @ColumnInfo(name = "upload_rejected") val uploadRejected: Boolean,
)

/** The packet store: every packet this node carries, its own and other people's. */
@Entity(tableName = "packets")
data class PacketEntity(
    @PrimaryKey val key: String,
    val type: String,
    val id: String,
    @ColumnInfo(name = "envelope_json") val envelopeJson: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "hops_left") val hopsLeft: Int,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "is_own") val isOwn: Boolean,
    @ColumnInfo(name = "upload_rejected") val uploadRejected: Boolean,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** A cart row joined with its catalog item. */
data class CartRow(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "store_id") val storeId: String,
    val qty: Int,
    @ColumnInfo(name = "on_unavailable") val onUnavailable: String,
    @ColumnInfo(name = "substitute_item_id") val substituteItemId: String?,
    val name: String,
    val price: Int,
    @ColumnInfo(name = "in_stock") val inStock: Boolean,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM stores ORDER BY position")
    fun stores(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM stores WHERE store_id = :storeId")
    fun store(storeId: String): Flow<StoreEntity?>

    @Query("SELECT * FROM stores WHERE store_id = :storeId")
    suspend fun getStore(storeId: String): StoreEntity?

    @Query("SELECT * FROM items WHERE store_id = :storeId ORDER BY position")
    fun items(storeId: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM catalog_meta WHERE id = 0")
    fun meta(): Flow<CatalogMetaEntity?>

    @Query("SELECT * FROM catalog_meta WHERE id = 0")
    suspend fun getMeta(): CatalogMetaEntity?

    @Query("SELECT MAX(stock_updated_at) FROM stores")
    fun latestStockUpdate(): Flow<Long?>

    @Query("DELETE FROM stores")
    suspend fun deleteStores()

    @Query("DELETE FROM items")
    suspend fun deleteItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStores(stores: List<StoreEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: CatalogMetaEntity)

    /** Replaces the whole cached catalog in one transaction. */
    @Transaction
    suspend fun replaceCatalog(stores: List<StoreEntity>, items: List<ItemEntity>, meta: CatalogMetaEntity) {
        deleteItems()
        deleteStores()
        insertStores(stores)
        insertItems(items)
        setMeta(meta)
    }
}

@Dao
interface CartDao {
    @Query(
        """SELECT c.item_id, c.store_id, c.qty, c.on_unavailable, c.substitute_item_id, i.name, i.price, i.in_stock
           FROM cart_items c JOIN items i ON i.item_id = c.item_id ORDER BY i.position"""
    )
    fun rows(): Flow<List<CartRow>>

    @Query(
        """SELECT c.item_id, c.store_id, c.qty, c.on_unavailable, c.substitute_item_id, i.name, i.price, i.in_stock
           FROM cart_items c JOIN items i ON i.item_id = c.item_id ORDER BY i.position"""
    )
    suspend fun getRows(): List<CartRow>

    @Query("SELECT * FROM cart_items WHERE item_id = :itemId")
    suspend fun get(itemId: String): CartItemEntity?

    @Query("SELECT store_id FROM cart_items LIMIT 1")
    suspend fun storeId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CartItemEntity)

    @Query("DELETE FROM cart_items WHERE item_id = :itemId")
    suspend fun remove(itemId: String)

    @Query("DELETE FROM cart_items")
    suspend fun clear()
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY created_at DESC")
    fun all(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE order_id = :orderId")
    fun order(orderId: String): Flow<OrderEntity?>

    @Query("SELECT * FROM orders WHERE order_id = :orderId")
    suspend fun get(orderId: String): OrderEntity?

    /** Own orders still waiting for an ACK that the server has not refused. */
    @Query("SELECT * FROM orders WHERE state IN ('QUEUED', 'RELAYED', 'UNCONFIRMED') AND upload_rejected = 0")
    suspend fun pendingUpload(): List<OrderEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(order: OrderEntity)

    @Query("UPDATE orders SET state = :state, ack_result_json = :ackResultJson, updated_at = :now WHERE order_id = :orderId")
    suspend fun setResult(orderId: String, state: String, ackResultJson: String, now: Long)

    @Query("UPDATE orders SET state = 'UNCONFIRMED', updated_at = :now WHERE state IN ('QUEUED', 'RELAYED') AND expires_at < :now")
    suspend fun markExpiredUnconfirmed(now: Long): Int

    @Query("UPDATE orders SET upload_rejected = 1, updated_at = :now WHERE order_id = :orderId")
    suspend fun markUploadRejected(orderId: String, now: Long)
}

@Dao
interface PacketDao {
    @Query("SELECT * FROM packets WHERE type = 'ORDER' AND upload_rejected = 0")
    suspend fun uploadableOrders(): List<PacketEntity>

    @Query("SELECT * FROM packets WHERE key = :key")
    suspend fun get(key: String): PacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(packet: PacketEntity)

    @Query("DELETE FROM packets WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM packets WHERE expires_at < :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("UPDATE packets SET upload_rejected = 1 WHERE key = :key")
    suspend fun markUploadRejected(key: String)

    @Query("SELECT COUNT(*) FROM packets")
    fun count(): Flow<Int>
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE key = :key")
    fun flow(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun remove(key: String)
}

@Database(
    entities = [
        StoreEntity::class, ItemEntity::class, CatalogMetaEntity::class, CartItemEntity::class,
        OrderEntity::class, PacketEntity::class, SettingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun cartDao(): CartDao
    abstract fun orderDao(): OrderDao
    abstract fun packetDao(): PacketDao
    abstract fun settingDao(): SettingDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "eternal-lite.db")
                // Hackathon build: a schema change wipes local data rather than needing a migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
