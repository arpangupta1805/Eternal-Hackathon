package com.eternallite.app.net

import com.eternallite.app.data.Address
import com.eternallite.app.mesh.Packet
import com.eternallite.app.mesh.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The backend could not be reached (no route, timeout, connection reset). The node is not online. */
class NetworkException(cause: Throwable) : IOException(cause.message, cause)

/** The backend answered, but not with 2xx. */
class HttpException(val code: Int, val body: String) : IOException("HTTP $code: $body")

@Serializable
data class EnrollRequest(
    val name: String,
    val phone: String,
    val address: Address,
    @SerialName("node_id") val nodeId: String,
    val role: String,
    @SerialName("sign_pk") val signPk: String,
    @SerialName("box_pk") val boxPk: String,
)

@Serializable
data class EnrollResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("server_sign_pk") val serverSignPk: String,
    @SerialName("server_box_pk") val serverBoxPk: String,
)

@Serializable
data class CatalogItemDto(
    @SerialName("item_id") val itemId: String,
    val name: String,
    val category: String,
    val price: Int,
    @SerialName("in_stock") val inStock: Boolean,
)

@Serializable
data class CatalogStoreDto(
    @SerialName("store_id") val storeId: String,
    val name: String,
    val kind: String,
    val open: Boolean,
    @SerialName("delivery_fee") val deliveryFee: Int,
    @SerialName("eta_minutes") val etaMinutes: Int,
    @SerialName("stock_seq") val stockSeq: Int,
    val items: List<CatalogItemDto>,
)

@Serializable
data class CatalogDto(
    @SerialName("catalog_version") val catalogVersion: Int,
    @SerialName("generated_at") val generatedAt: Long,
    val stores: List<CatalogStoreDto>,
)

@Serializable
data class UploadRequest(
    @SerialName("gateway_node_id") val gatewayNodeId: String,
    val packets: List<Packet>,
)

@Serializable
data class PacketsResponse(val packets: List<Packet>)

/** HTTP calls to the backend (docs/04-backend.md, "Public API"). No Android classes, so it runs in JVM tests. */
class ApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Protocol.json
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun enroll(baseUrl: String, request: EnrollRequest): EnrollResponse =
        post(baseUrl, "/v1/devices/enroll", EnrollRequest.serializer(), request, EnrollResponse.serializer())

    suspend fun catalog(baseUrl: String): CatalogDto =
        get(baseUrl, "/v1/catalog", CatalogDto.serializer())

    /** Uploads ORDER packets. The response holds one ACK per order the server processed or already knew. */
    suspend fun uploadPackets(baseUrl: String, gatewayNodeId: String, packets: List<Packet>): List<Packet> =
        post(baseUrl, "/v1/mesh/packets", UploadRequest.serializer(), UploadRequest(gatewayNodeId, packets), PacketsResponse.serializer()).packets

    suspend fun stock(baseUrl: String): List<Packet> =
        get(baseUrl, "/v1/mesh/stock", PacketsResponse.serializer()).packets

    private suspend fun <T> get(baseUrl: String, path: String, out: KSerializer<T>): T =
        execute(Request.Builder().url(url(baseUrl, path)).get().build(), out)

    private suspend fun <I, T> post(baseUrl: String, path: String, inSer: KSerializer<I>, body: I, out: KSerializer<T>): T {
        val text = json.encodeToString(inSer, body)
        return execute(Request.Builder().url(url(baseUrl, path)).post(text.toRequestBody(jsonMedia)).build(), out)
    }

    private fun url(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + path

    private suspend fun <T> execute(request: Request, out: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw NetworkException(e)
        } catch (e: IllegalArgumentException) {
            throw NetworkException(e) // malformed server URL
        }
        if (code !in 200..299) throw HttpException(code, text.take(300))
        try {
            json.decodeFromString(out, text)
        } catch (e: Exception) {
            // Something answered, but it is not our backend (captive portal, wrong URL).
            throw HttpException(code, "unexpected response")
        }
    }
}
