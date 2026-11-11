package com.eternallite.app.crypto

import com.eternallite.app.data.AckHeader
import com.eternallite.app.data.AckResult
import com.eternallite.app.data.OrderBody
import com.eternallite.app.mesh.Packet
import com.eternallite.app.mesh.Protocol

/** The device's four keys. Secret keys never leave the device. */
class DeviceKeys(
    val signPk: ByteArray,
    val signSk: ByteArray,
    val boxPk: ByteArray,
    val boxSk: ByteArray,
)

/** Signs and seals an order into an ORDER packet (docs/03-protocol.md, "ORDER payload"). */
class OrderSealer(private val sodium: Sodium) {

    /** Returns the packet and the exact body JSON that was signed. */
    fun seal(body: OrderBody, deviceSignSk: ByteArray, serverBoxPk: ByteArray, nodeId: String): Pair<Packet, String> {
        val bodyJson = Protocol.json.encodeToString(OrderBody.serializer(), body)
        val signed = sodium.sign(bodyJson.toByteArray(Charsets.UTF_8), deviceSignSk)
        val sealed = sodium.seal(signed, serverBoxPk)
        val packet = Packet(
            type = Protocol.TYPE_ORDER,
            id = body.orderId,
            expiresAt = body.expiresAt,
            hopsLeft = Protocol.INITIAL_HOPS,
            trace = listOf(nodeId),
            payload = B64.encode(sealed),
        )
        return packet to bodyJson
    }
}

/** Checks server signatures. Every ACK and STOCK goes through here before it has any effect. */
class SignatureVerifier(private val sodium: Sodium) {

    /**
     * Verifies an ACK packet against the server signing key, on the exact bytes received.
     * Returns the header only if the signature is valid and its order_id equals the envelope id.
     */
    fun verifyAck(packet: Packet, serverSignPk: ByteArray): AckHeader? {
        if (packet.type != Protocol.TYPE_ACK) return null
        val signed = B64.decode(packet.payload) ?: return null
        val bytes = sodium.signOpen(signed, serverSignPk) ?: return null
        val header = try {
            Protocol.json.decodeFromString(AckHeader.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        return if (header.orderId == packet.id) header else null
    }
}

/** Opens the sealed result inside a verified ACK. Only the originator's keys can. */
class AckOpener(private val sodium: Sodium) {

    fun open(header: AckHeader, keys: DeviceKeys): AckResult? {
        val sealed = B64.decode(header.sealedResult) ?: return null
        val bytes = sodium.sealOpen(sealed, keys.boxPk, keys.boxSk) ?: return null
        val result = try {
            Protocol.json.decodeFromString(AckResult.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        return if (result.orderId == header.orderId) result else null
    }
}
