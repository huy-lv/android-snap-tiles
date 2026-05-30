package com.snap.tiles.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

object ConnectToMacRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun getDeviceLanIp(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val raw = wm.connectionInfo?.ipAddress ?: return null
        if (raw == 0) return null
        val ordered = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
            Integer.reverseBytes(raw) else raw
        return InetAddress.getByAddress(
            byteArrayOf(
                (ordered shr 24 and 0xFF).toByte(),
                (ordered shr 16 and 0xFF).toByte(),
                (ordered shr 8 and 0xFF).toByte(),
                (ordered and 0xFF).toByte()
            )
        ).hostAddress
    }

    suspend fun sendConnectRequest(macIp: String, macPort: Int, phoneIp: String): ConnectResult =
        withContext(Dispatchers.IO) {
            val url = "http://$macIp:$macPort/connect"
            val body = JSONObject().put("phone_ip", phoneIp).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val status = json.optString("status")
                    val message = json.optString("message")
                    when (status) {
                        "ok" -> ConnectResult.Success(message)
                        "needs_pairing" -> ConnectResult.NeedsPairing(message)
                        else -> ConnectResult.Failure(message)
                    }
                }
            } catch (e: java.net.ConnectException) {
                ConnectResult.Failure("Cannot reach Mac daemon. Is it running? Check firewall.")
            } catch (e: java.net.SocketTimeoutException) {
                ConnectResult.Failure("Connection timed out. Daemon may be busy.")
            } catch (e: Exception) {
                ConnectResult.Failure(e.message ?: "Unknown error")
            }
        }
}

sealed class ConnectResult {
    data class Success(val message: String) : ConnectResult()
    data class NeedsPairing(val instructions: String) : ConnectResult()
    data class Failure(val message: String) : ConnectResult()
}
