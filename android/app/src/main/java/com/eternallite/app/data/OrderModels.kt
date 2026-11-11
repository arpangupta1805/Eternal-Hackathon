package com.eternallite.app.data

import com.eternallite.app.mesh.Protocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for docs/03-protocol.md. Field names are the protocol's; do not rename.

@Serializable
data class Address(
    val line1: String,
    val line2: String = "",
    val landmark: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class OrderLine(
    @SerialName("item_id") val itemId: String,
    val qty: Int,
    @SerialName("unit_price_seen") val unitPriceSeen: Int,
    @SerialName("on_unavailable") val onUnavailable: String,
    @SerialName("substitute_item_id") val substituteItemId: String?,
)

@Serializable
data class OrderBody(
    val v: Int = Protocol.VERSION,
    @SerialName("order_id") val orderId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("catalog_version") val catalogVersion: Int,
    @SerialName("store_id") val storeId: String,
    val items: List<OrderLine>,
    @SerialName("delivery_fee_seen") val deliveryFeeSeen: Int,
    @SerialName("total_seen") val totalSeen: Int,
    @SerialName("max_total") val maxTotal: Int,
    val payment: String = "COD",
    val address: Address,
    val phone: String,
    val note: String = "",
)

/** The signed, unencrypted part of an ACK payload. Any node can read it. */
@Serializable
data class AckHeader(
    @SerialName("order_id") val orderId: String,
    @SerialName("issued_at") val issuedAt: Long,
    @SerialName("sealed_result") val sealedResult: String,
)

@Serializable
data class FinalItem(
    @SerialName("item_id") val itemId: String,
    val name: String,
    val qty: Int,
    @SerialName("unit_price") val unitPrice: Int,
)

@Serializable
data class OrderChange(
    @SerialName("item_id") val itemId: String,
    val change: String, // DROPPED | SUBSTITUTED | PRICE_CHANGED
    @SerialName("new_item_id") val newItemId: String? = null,
    @SerialName("old_price") val oldPrice: Int? = null,
    @SerialName("new_price") val newPrice: Int? = null,
)

/** The server's final answer for one order. Only the originator can open it. */
@Serializable
data class AckResult(
    @SerialName("order_id") val orderId: String,
    val status: String, // CONFIRMED | CONFIRMED_MODIFIED | REJECTED
    val reason: String? = null,
    @SerialName("server_order_ref") val serverOrderRef: String? = null,
    @SerialName("final_items") val finalItems: List<FinalItem> = emptyList(),
    val changes: List<OrderChange> = emptyList(),
    @SerialName("delivery_fee") val deliveryFee: Int = 0,
    @SerialName("final_total") val finalTotal: Int = 0,
    @SerialName("eta_minutes") val etaMinutes: Int? = null,
)

object OnUnavailable {
    const val DROP = "DROP"
    const val SUBSTITUTE = "SUBSTITUTE"
    const val CANCEL_ORDER = "CANCEL_ORDER"
}

/** Order states on the originator (docs/03-protocol.md, "Order states on the originator"). */
object OrderState {
    const val QUEUED = "QUEUED"
    const val RELAYED = "RELAYED"
    const val CONFIRMED = "CONFIRMED"
    const val CONFIRMED_MODIFIED = "CONFIRMED_MODIFIED"
    const val REJECTED = "REJECTED"
    const val UNCONFIRMED = "UNCONFIRMED"

    /** States that still wait for an ACK. */
    val pending = listOf(QUEUED, RELAYED, UNCONFIRMED)
}

/** One cart line with the price the user saw, ready to go into an order. */
data class CartLine(
    val itemId: String,
    val qty: Int,
    val unitPrice: Int,
    val onUnavailable: String = OnUnavailable.DROP,
    val substituteItemId: String? = null,
)

/** Builds an OrderBody. Pure, so it can be unit tested without Android. */
object OrderBuilder {
    fun build(
        orderId: String,
        deviceId: String,
        now: Long,
        catalogVersion: Int,
        storeId: String,
        deliveryFee: Int,
        lines: List<CartLine>,
        tolerancePaise: Int,
        address: Address,
        phone: String,
        note: String = "",
    ): OrderBody {
        require(lines.isNotEmpty()) { "an order needs at least one item" }
        require(lines.all { it.qty > 0 }) { "quantities must be positive" }
        require(tolerancePaise >= 0) { "tolerance cannot be negative" }
        val totalSeen = lines.sumOf { it.unitPrice * it.qty } + deliveryFee
        return OrderBody(
            orderId = orderId,
            deviceId = deviceId,
            createdAt = now,
            expiresAt = now + Protocol.ORDER_VALIDITY_MS,
            catalogVersion = catalogVersion,
            storeId = storeId,
            items = lines.map {
                val substitute = if (it.onUnavailable == OnUnavailable.SUBSTITUTE) it.substituteItemId else null
                OrderLine(
                    itemId = it.itemId,
                    qty = it.qty,
                    unitPriceSeen = it.unitPrice,
                    // SUBSTITUTE with nothing picked means the same as DROP.
                    onUnavailable = if (it.onUnavailable == OnUnavailable.SUBSTITUTE && substitute == null) OnUnavailable.DROP else it.onUnavailable,
                    substituteItemId = substitute,
                )
            },
            deliveryFeeSeen = deliveryFee,
            totalSeen = totalSeen,
            maxTotal = totalSeen + tolerancePaise,
            address = address,
            phone = phone,
            note = note,
        )
    }
}
