package com.eternallite.app.mesh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Constants from docs/03-protocol.md. Must match backend/src/constants.js. */
object Protocol {
    const val VERSION = 1
    const val ORDER_VALIDITY_MS = 20 * 60 * 1000L
    const val INITIAL_HOPS = 8
    const val MAX_PACKET_BYTES = 16384
    const val MAX_STORED_PACKETS = 200
    const val MAX_TRACE = 16
    const val STOCK_POLL_MS = 10_000L
    const val ONLINE_RETRY_MS = 10_000L
    const val EXPIRY_SWEEP_MS = 30_000L
    const val DEFAULT_PRICE_TOLERANCE = 2000

    const val TYPE_ORDER = "ORDER"
    const val TYPE_ACK = "ACK"
    const val TYPE_STOCK = "STOCK"

    /**
     * One JSON configuration for everything on the wire.
     * Defaults and nulls are always written, unknown fields from a newer server are ignored.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
}

/** The packet envelope. Relays can read all of it; it is not authenticated. */
@Serializable
data class Packet(
    val v: Int = Protocol.VERSION,
    val type: String,
    val id: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("hops_left") val hopsLeft: Int,
    val trace: List<String>,
    val payload: String,
) {
    /** Used for dedupe and the inventory exchange, e.g. "ORDER:3f2b...". */
    val key: String get() = "$type:$id"

    fun toJson(): String = Protocol.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): Packet = Protocol.json.decodeFromString(serializer(), text)
    }
}
