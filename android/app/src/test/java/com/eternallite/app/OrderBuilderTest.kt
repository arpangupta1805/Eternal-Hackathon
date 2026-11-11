package com.eternallite.app

import com.eternallite.app.data.Address
import com.eternallite.app.data.CartLine
import com.eternallite.app.data.OnUnavailable
import com.eternallite.app.data.OrderBody
import com.eternallite.app.data.OrderBuilder
import com.eternallite.app.mesh.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBuilderTest {
    private val address = Address(line1 = "Room 214, Hostel H", line2 = "IIT Gandhinagar", landmark = "Near mess")

    private fun build(lines: List<CartLine>, tolerance: Int = Protocol.DEFAULT_PRICE_TOLERANCE) = OrderBuilder.build(
        orderId = "3f2b6c1e-9d47-4a0b-8a51-0c2e7d9b1a44",
        deviceId = "dev_7k2m9x",
        now = 1_794_478_800_000,
        catalogVersion = 12,
        storeId = "store_blinkit_01",
        deliveryFee = 2500,
        lines = lines,
        tolerancePaise = tolerance,
        address = address,
        phone = "9000000000",
    )

    @Test
    fun totalsMatchTheProtocolExample() {
        val body = build(
            listOf(
                CartLine("itm_milk_500", 2, 3300),
                CartLine("itm_bread_brown", 1, 5500, OnUnavailable.SUBSTITUTE, "itm_bread_white"),
            )
        )
        assertEquals(14600, body.totalSeen)
        assertEquals(16600, body.maxTotal)
        assertEquals(2500, body.deliveryFeeSeen)
        assertEquals(1_794_480_000_000, body.expiresAt)
        assertEquals("COD", body.payment)
        assertEquals("itm_bread_white", body.items[1].substituteItemId)
    }

    @Test
    fun substituteWithNothingPickedBecomesDrop() {
        val body = build(listOf(CartLine("itm_milk_500", 1, 3300, OnUnavailable.SUBSTITUTE, null)))
        assertEquals(OnUnavailable.DROP, body.items[0].onUnavailable)
        assertNull(body.items[0].substituteItemId)
    }

    @Test
    fun substituteIdIsNotSentForOtherRules() {
        val body = build(listOf(CartLine("itm_milk_500", 1, 3300, OnUnavailable.CANCEL_ORDER, "itm_bread_white")))
        assertEquals(OnUnavailable.CANCEL_ORDER, body.items[0].onUnavailable)
        assertNull(body.items[0].substituteItemId)
    }

    @Test
    fun jsonUsesProtocolFieldNamesAndWritesNulls() {
        val body = build(listOf(CartLine("itm_milk_500", 2, 3300)), tolerance = 0)
        val text = Protocol.json.encodeToString(OrderBody.serializer(), body)
        for (field in listOf(
            "\"v\":1", "\"order_id\":", "\"device_id\":", "\"created_at\":", "\"expires_at\":", "\"catalog_version\":12",
            "\"store_id\":", "\"unit_price_seen\":3300", "\"on_unavailable\":\"DROP\"", "\"substitute_item_id\":null",
            "\"delivery_fee_seen\":2500", "\"total_seen\":9100", "\"max_total\":9100", "\"payment\":\"COD\"", "\"note\":\"\"",
        )) {
            assertTrue("missing $field in $text", text.contains(field))
        }
        assertEquals(body, Protocol.json.decodeFromString(OrderBody.serializer(), text))
    }
}
