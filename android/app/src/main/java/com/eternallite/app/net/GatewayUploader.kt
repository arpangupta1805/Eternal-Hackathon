package com.eternallite.app.net

import com.eternallite.app.crypto.AckOpener
import com.eternallite.app.crypto.B64
import com.eternallite.app.crypto.DeviceKeyStore
import com.eternallite.app.crypto.SignatureVerifier
import com.eternallite.app.data.AckResult
import com.eternallite.app.data.AppDatabase
import com.eternallite.app.data.PacketEntity
import com.eternallite.app.data.Settings
import com.eternallite.app.mesh.Packet
import com.eternallite.app.mesh.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Handles an ACK packet that came back from the backend (docs/03-protocol.md, routing rule 5, ACK).
 * In M4 the mesh router takes over packets that arrive from peers; this stays the entry for upload responses.
 */
class AckHandler(
    private val db: AppDatabase,
    private val settings: Settings,
    private val keyStore: DeviceKeyStore,
    private val verifier: SignatureVerifier,
    private val opener: AckOpener,
    private val clock: () -> Long,
) {
    /** Returns true if the ACK was genuine and has been applied. */
    suspend fun handle(packet: Packet): Boolean {
        val serverSignPk = settings.get(Settings.SERVER_SIGN_PK)?.let(B64::decode) ?: return false
        val header = verifier.verifyAck(packet, serverSignPk) ?: return false // forged or damaged: no effect
        val nodeId = settings.get(Settings.NODE_ID).orEmpty()
        val now = clock()

        // The ACK replaces the order in the packet store and stays as a tombstone.
        db.packetDao().delete("${Protocol.TYPE_ORDER}:${packet.id}")
        val own = db.orderDao().get(packet.id)
        val stored = if (packet.trace.size < Protocol.MAX_TRACE) packet.copy(trace = packet.trace + nodeId) else packet
        db.packetDao().upsert(
            PacketEntity(
                key = stored.key,
                type = stored.type,
                id = stored.id,
                envelopeJson = stored.toJson(),
                expiresAt = stored.expiresAt,
                hopsLeft = stored.hopsLeft,
                receivedAt = now,
                isOwn = own != null,
                uploadRejected = false,
            )
        )

        if (own != null) {
            val result = opener.open(header, keyStore.getOrCreate()) ?: return false
            db.orderDao().setResult(
                orderId = own.orderId,
                state = result.status,
                ackResultJson = Protocol.json.encodeToString(AckResult.serializer(), result),
                now = now,
            )
        }
        return true
    }
}

/**
 * Gateway behaviour: whenever this node is online it uploads every ORDER it holds,
 * plus the user's own orders that still have no answer. The self-relay queue is this same code.
 */
class GatewayUploader(
    private val db: AppDatabase,
    private val settings: Settings,
    private val api: ApiClient,
    private val connectivity: ConnectivityMonitor,
    private val ackHandler: AckHandler,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        // Upload the moment the node becomes online. Do not wait for a timer.
        scope.launch { connectivity.isOnline.collect { online -> if (online) kick() } }
        scope.launch {
            for (unused in trigger) {
                try {
                    uploadOnce()
                } catch (e: Exception) {
                    // Never let one bad round stop the uploader.
                }
            }
        }
    }

    /** Ask for an upload round now. Safe to call from anywhere, any number of times. */
    fun kick() {
        trigger.trySend(Unit)
    }

    private suspend fun uploadOnce() {
        if (!connectivity.isOnline.value) return
        val url = settings.get(Settings.SERVER_URL) ?: return
        val nodeId = settings.get(Settings.NODE_ID) ?: return

        val byId = LinkedHashMap<String, Packet>()
        for (p in db.packetDao().uploadableOrders()) byId[p.id] = Packet.fromJson(p.envelopeJson)
        // Own orders are kept outside the packet store too, so they can be re-sent after the packet expired.
        for (o in db.orderDao().pendingUpload()) byId.getOrPut(o.orderId) { Packet.fromJson(o.envelopeJson) }
        if (byId.isEmpty()) return

        val acks = try {
            api.uploadPackets(url, nodeId, byId.values.toList()).also { connectivity.reportBackendOk() }
        } catch (e: NetworkException) {
            connectivity.reportBackendFailure() // the retry probe brings the node back online
            return
        } catch (e: HttpException) {
            return // the server answered but not with 200: nothing is marked, retried next round
        }

        val answered = HashSet<String>()
        for (ack in acks) {
            if (ack.type != Protocol.TYPE_ACK) continue
            answered += ack.id
            ackHandler.handle(ack)
        }

        // HTTP 200 with no ACK for an order means the server dropped it. Never upload that copy again.
        val now = clock()
        for (id in byId.keys) {
            if (id in answered) continue
            db.packetDao().markUploadRejected("${Protocol.TYPE_ORDER}:$id")
            db.orderDao().markUploadRejected(id, now)
        }
    }
}
