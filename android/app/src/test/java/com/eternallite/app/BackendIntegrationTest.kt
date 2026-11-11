package com.eternallite.app

import com.eternallite.app.crypto.AckOpener
import com.eternallite.app.crypto.B64
import com.eternallite.app.crypto.DeviceKeys
import com.eternallite.app.crypto.OrderSealer
import com.eternallite.app.crypto.SignatureVerifier
import com.eternallite.app.data.Address
import com.eternallite.app.data.CartLine
import com.eternallite.app.data.OnUnavailable
import com.eternallite.app.data.OrderBuilder
import com.eternallite.app.net.ApiClient
import com.eternallite.app.net.EnrollRequest
import com.eternallite.app.net.NetworkException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Runs the app's real enroll, seal, upload and ACK-open code against a running backend.
 * This is the byte-for-byte check between Kotlin and Node.
 *
 *   cd backend && npm run dev
 *   cd android && ETERNAL_BACKEND_URL=http://localhost:8080 ./gradlew testDebugUnitTest
 *
 * Skipped when ETERNAL_BACKEND_URL is not set.
 */
class BackendIntegrationTest {
    private val url: String = System.getenv("ETERNAL_BACKEND_URL").orEmpty()
    private val sodium = testSodium
    private val api = ApiClient()

    private class Enrolled(val deviceId: String, val nodeId: String, val keys: DeviceKeys, val serverSignPk: ByteArray, val serverBoxPk: ByteArray)

    private suspend fun enroll(role: String = "CUSTOMER"): Enrolled {
        val sign = sodium.signKeypair()
        val box = sodium.boxKeypair()
        val nodeId = UUID.randomUUID().toString().replace("-", "").take(8)
        val res = api.enroll(url, EnrollRequest("Kotlin Test", "9000000000", Address("Room 214", "IIT Gandhinagar"), nodeId, role, B64.encode(sign.pk), B64.encode(box.pk)))
        return Enrolled(res.deviceId, nodeId, DeviceKeys(sign.pk, sign.sk, box.pk, box.sk), B64.decode(res.serverSignPk)!!, B64.decode(res.serverBoxPk)!!)
    }

    @Test
    fun orderSealedInKotlinIsConfirmedByTheNodeBackend() = runBlocking {
        assumeTrue("ETERNAL_BACKEND_URL not set", url.isNotEmpty())
        val device = enroll()
        assertTrue(device.deviceId.startsWith("dev_"))

        val catalog = api.catalog(url)
        val store = catalog.stores.first { it.storeId == "store_blinkit_01" }
        val milk = store.items.first { it.itemId == "itm_milk_500" }
        val brown = store.items.first { it.itemId == "itm_bread_brown" }

        val body = OrderBuilder.build(
            orderId = UUID.randomUUID().toString(),
            deviceId = device.deviceId,
            now = System.currentTimeMillis(),
            catalogVersion = catalog.catalogVersion,
            storeId = store.storeId,
            deliveryFee = store.deliveryFee,
            lines = listOf(
                CartLine(milk.itemId, 2, milk.price),
                CartLine(brown.itemId, 1, brown.price, OnUnavailable.SUBSTITUTE, "itm_bread_white"),
            ),
            tolerancePaise = 2000,
            address = Address("Room 214", "IIT Gandhinagar", "Near mess"),
            phone = "9000000000",
        )
        val (packet, _) = OrderSealer(sodium).seal(body, device.keys.signSk, device.serverBoxPk, device.nodeId)

        val acks = api.uploadPackets(url, device.nodeId, listOf(packet))
        assertEquals("the backend must answer a valid Kotlin-sealed order with one ACK", 1, acks.size)

        val header = SignatureVerifier(sodium).verifyAck(acks[0], device.serverSignPk)
        assertNotNull("server signature on the ACK must verify in Kotlin", header)
        val result = AckOpener(sodium).open(header!!, device.keys)
        assertNotNull("sealed result must open with the device box keys", result)
        assertEquals(body.orderId, result!!.orderId)
        assertTrue("unexpected status ${result.status}", result.status == "CONFIRMED" || result.status == "CONFIRMED_MODIFIED")
        assertTrue(result.serverOrderRef!!.startsWith("EL-"))
        assertTrue(result.finalTotal > 0)

        // Uploading again returns the identical ACK.
        assertEquals(acks, api.uploadPackets(url, device.nodeId, listOf(packet)))

        // A tampered copy of a new order gets no ACK.
        val body2 = body.copy(orderId = UUID.randomUUID().toString())
        val (packet2, _) = OrderSealer(sodium).seal(body2, device.keys.signSk, device.serverBoxPk, device.nodeId)
        val bytes = B64.decode(packet2.payload)!!
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        assertTrue(api.uploadPackets(url, device.nodeId, listOf(packet2.copy(payload = B64.encode(bytes)))).isEmpty())

        // Stock packets parse.
        assertEquals(2, api.stock(url).size)
    }

    @Test
    fun unreachableServerIsANetworkException() = runBlocking {
        try {
            api.catalog("http://127.0.0.1:9")
            fail("expected NetworkException")
        } catch (e: NetworkException) {
            // expected
        }
    }
}
