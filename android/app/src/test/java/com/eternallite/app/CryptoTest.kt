package com.eternallite.app

import com.eternallite.app.crypto.AckOpener
import com.eternallite.app.crypto.B64
import com.eternallite.app.crypto.DeviceKeys
import com.eternallite.app.crypto.OrderSealer
import com.eternallite.app.crypto.SignatureVerifier
import com.eternallite.app.data.AckHeader
import com.eternallite.app.data.AckResult
import com.eternallite.app.data.Address
import com.eternallite.app.data.CartLine
import com.eternallite.app.data.OrderBody
import com.eternallite.app.data.OrderBuilder
import com.eternallite.app.mesh.Packet
import com.eternallite.app.mesh.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Plays the server's part with the same primitives, to check the device side end to end. */
class CryptoTest {
    private val sodium = testSodium
    private val serverSign = sodium.signKeypair()
    private val serverBox = sodium.boxKeypair()
    private val deviceSign = sodium.signKeypair()
    private val deviceBox = sodium.boxKeypair()
    private val keys = DeviceKeys(deviceSign.pk, deviceSign.sk, deviceBox.pk, deviceBox.sk)

    private val body = OrderBuilder.build(
        orderId = "3f2b6c1e-9d47-4a0b-8a51-0c2e7d9b1a44",
        deviceId = "dev_test01",
        now = 1_794_478_800_000,
        catalogVersion = 1,
        storeId = "store_blinkit_01",
        deliveryFee = 2500,
        lines = listOf(CartLine("itm_milk_500", 2, 3300)),
        tolerancePaise = 2000,
        address = Address("Room 214"),
        phone = "9000000000",
    )

    private fun ackPacket(result: AckResult, signSk: ByteArray = serverSign.sk, envelopeId: String = result.orderId): Packet {
        val sealed = sodium.seal(Protocol.json.encodeToString(AckResult.serializer(), result).toByteArray(), deviceBox.pk)
        val header = AckHeader(result.orderId, 1_794_478_900_000, B64.encode(sealed))
        val signed = sodium.sign(Protocol.json.encodeToString(AckHeader.serializer(), header).toByteArray(), signSk)
        return Packet(type = "ACK", id = envelopeId, expiresAt = 1_794_482_500_000, hopsLeft = 8, trace = emptyList(), payload = B64.encode(signed))
    }

    @Test
    fun sealedOrderOpensOnTheServerSideAndVerifies() {
        val (packet, bodyJson) = OrderSealer(sodium).seal(body, deviceSign.sk, serverBox.pk, "a1b2c3d4")

        assertEquals("ORDER", packet.type)
        assertEquals(body.orderId, packet.id)
        assertEquals(body.expiresAt, packet.expiresAt)
        assertEquals(8, packet.hopsLeft)
        assertEquals(listOf("a1b2c3d4"), packet.trace)
        assertEquals("ORDER:${body.orderId}", packet.key)

        val signed = sodium.sealOpen(B64.decode(packet.payload)!!, serverBox.pk, serverBox.sk)
        assertNotNull(signed)
        // First 64 bytes are the signature, the rest is exactly the body JSON.
        assertArrayEquals(bodyJson.toByteArray(), signed!!.copyOfRange(64, signed.size))
        assertArrayEquals(bodyJson.toByteArray(), sodium.signOpen(signed, deviceSign.pk))
        assertEquals(body, Protocol.json.decodeFromString(OrderBody.serializer(), bodyJson))
    }

    @Test
    fun aRelayCannotOpenTheOrder() {
        val (packet, _) = OrderSealer(sodium).seal(body, deviceSign.sk, serverBox.pk, "a1b2c3d4")
        val relay = sodium.boxKeypair()
        assertNull(sodium.sealOpen(B64.decode(packet.payload)!!, relay.pk, relay.sk))
    }

    @Test
    fun flippingOneByteBreaksTheSeal() {
        val (packet, _) = OrderSealer(sodium).seal(body, deviceSign.sk, serverBox.pk, "a1b2c3d4")
        val bytes = B64.decode(packet.payload)!!
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        assertNull(sodium.sealOpen(bytes, serverBox.pk, serverBox.sk))
    }

    @Test
    fun ackIsVerifiedAndOpened() {
        val result = AckResult(orderId = body.orderId, status = "CONFIRMED", serverOrderRef = "EL-000001", finalTotal = 9100, deliveryFee = 2500, etaMinutes = 12)
        val packet = ackPacket(result)
        val header = SignatureVerifier(sodium).verifyAck(packet, serverSign.pk)
        assertNotNull(header)
        assertEquals(result, AckOpener(sodium).open(header!!, keys))
    }

    @Test
    fun forgedAckIsRejected() {
        val result = AckResult(orderId = body.orderId, status = "CONFIRMED")
        val attacker = sodium.signKeypair()
        assertNull(SignatureVerifier(sodium).verifyAck(ackPacket(result, signSk = attacker.sk), serverSign.pk))
    }

    @Test
    fun ackWithModifiedPayloadIsRejected() {
        val packet = ackPacket(AckResult(orderId = body.orderId, status = "CONFIRMED"))
        val bytes = B64.decode(packet.payload)!!
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 1).toByte()
        assertNull(SignatureVerifier(sodium).verifyAck(packet.copy(payload = B64.encode(bytes)), serverSign.pk))
    }

    @Test
    fun ackForAnotherOrderIdIsRejected() {
        val packet = ackPacket(AckResult(orderId = body.orderId, status = "CONFIRMED"), envelopeId = "some-other-order")
        assertNull(SignatureVerifier(sodium).verifyAck(packet, serverSign.pk))
    }

    @Test
    fun anotherDeviceCannotOpenTheResult() {
        val packet = ackPacket(AckResult(orderId = body.orderId, status = "CONFIRMED"))
        val header = SignatureVerifier(sodium).verifyAck(packet, serverSign.pk)!!
        val other = sodium.boxKeypair()
        assertNull(AckOpener(sodium).open(header, DeviceKeys(deviceSign.pk, deviceSign.sk, other.pk, other.sk)))
    }

    @Test
    fun packetJsonRoundTripsWithProtocolFieldNames() {
        val (packet, _) = OrderSealer(sodium).seal(body, deviceSign.sk, serverBox.pk, "a1b2c3d4")
        val text = packet.toJson()
        for (field in listOf("\"v\":1", "\"type\":\"ORDER\"", "\"expires_at\":", "\"hops_left\":8", "\"trace\":[\"a1b2c3d4\"]", "\"payload\":")) {
            assert(text.contains(field)) { "missing $field in $text" }
        }
        assertEquals(packet, Packet.fromJson(text))
    }

    @Test
    fun base64IsStandardWithPadding() {
        assertEquals("+/8=", B64.encode(byteArrayOf(-5, -1)))
        assertArrayEquals(byteArrayOf(-5, -1), B64.decode("+/8="))
        assertNull(B64.decode("not base64!"))
    }
}
