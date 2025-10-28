package com.bestlink.screenmate.net

import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.util.Log
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.net.ssl.TrustManager
import javax.net.ssl.HostnameVerifier
import java.security.SecureRandom
import java.security.cert.X509Certificate

class WsClient(
    private val host: Host,
    private val name: String,
    private val initialId: String,
    private val useTls: Boolean = false
) {
    private val TAG = "WsClient"
    private val client = run {
        val builder = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (useTls) {
            // 开发/测试用：信任所有证书与宽松主机名校验，生产环境请改为证书固定或内置CA
            val trustAllCerts = arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts as Array<TrustManager>, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0])
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
            Log.w(TAG, "TLS enabled with trust-all (DEV ONLY). Use certificate pinning in production.")
        }
        builder.build()
    }

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var missCount = 0

    suspend fun connect(): Boolean = withTimeoutOrNull(5000) {
        val scheme = if (useTls) "wss" else "ws"
        Log.d(TAG, "Connecting to $scheme://${host.ip}:${host.port}/")
        suspendCancellableCoroutine<Boolean> { cont ->
            val request = Request.Builder().url("$scheme://${host.ip}:${host.port}/").build()
            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket onOpen: ${host.ip}:${host.port}")
                    webSocket = ws
                    sendHandshake()
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "onMessage from ${host.ip}: $text")
                    try {
                        val json = JSONObject(text)
                        val result = json.optString("result", "")
                        val id = json.optString("id", "")
                        val name = json.optString("name", "")
                        val tagId = json.optString("tag_id", "")
                        
                        if (result == "success" && id.isNotEmpty()) {
                            host.id = id
                            // 更新主机名称和tag_id
                            if (name.isNotEmpty()) {
                                host.name = name
                            }
                            if (tagId.isNotEmpty()) {
                                host.tagId = tagId
                            }
                            host.connected = true
                            missCount = 0
                            if (cont.isActive) {
                                Log.d(TAG, "Handshake success, id=$id, name=$name, tag_id=$tagId")
                                cont.resume(true)
                            }
                        } else {
                            Log.w(TAG, "Message parsed but not success: result='$result', id='$id'")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse message JSON from ${host.ip}: ${e.message}", e)
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure on ${host.ip}:${host.port}: ${t.message}", t)
                    host.connected = false
                    if (cont.isActive) cont.resume(false)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closed ${host.ip}:${host.port}, code=$code, reason=$reason")
                    host.connected = false
                    if (cont.isActive) cont.resume(false)
                }
            }
            val ws = client.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                Log.w(TAG, "Connection cancelled for ${host.ip}")
                try { ws.cancel() } catch (_: Exception) {}
            }
        }
    } ?: run {
        Log.w(TAG, "Connect timeout for ${host.ip}:${host.port}")
        false
    }

    fun startHeartbeat(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            while (isActive && webSocket != null) {
                delay(5000)
                Log.d(TAG, "Heartbeat -> handshake to ${host.ip}")
                val ok = sendHandshake()
                if (!ok) {
                    missCount++
                    Log.w(TAG, "Heartbeat send failed to ${host.ip}, missCount=$missCount")
                    if (missCount >= 3) {
                        Log.w(TAG, "Heartbeat missed 3 times, disconnecting ${host.ip}")
                        disconnect()
                        break
                    }
                } else {
                    missCount = 0
                }
            }
        }
    }

    fun sendHandshake(): Boolean {
        val msg = """{"command":"handshake","name":"$name","id":"$initialId"}"""
        val sent = webSocket?.send(msg) == true
        Log.d(TAG, "sendHandshake to ${host.ip}, sent=$sent, payload=$msg")
        return sent
    }

    fun sendCommand(command: String): Boolean {
        // 保留兼容旧格式的发送（如需）
        val payload = """{"command":"$command"}"""
        val sent = webSocket?.send(payload) == true
        Log.d(TAG, "sendCommand to ${host.ip}, cmd=$command, sent=$sent")
        return sent
    }

    fun sendKey(content: String): Boolean {
        if (webSocket == null || !host.connected) {
            Log.w(TAG, "sendKey failed: WebSocket not connected for ${host.ip}")
            return false
        }
        val id = host.id ?: initialId
        val payload = """{"id":"$id","command":"keypress","content":"$content"}"""
        val sent = webSocket?.send(payload) == true
        Log.d(TAG, "sendKey to ${host.ip}, payload=$payload, sent=$sent")
        return sent
    }

    fun sendRawMessage(message: String): Boolean {
        if (webSocket == null || !host.connected) {
            Log.w(TAG, "sendRawMessage failed: WebSocket not connected for ${host.ip}")
            return false
        }
        val sent = webSocket?.send(message) == true
        Log.d(TAG, "sendRawMessage to ${host.ip}, message=$message, sent=$sent")
        return sent
    }

    suspend fun ensureConnectedAndSendKey(content: String): Boolean {
        if (webSocket == null || !host.connected) {
            Log.w(TAG, "ensureConnectedAndSendKey: not connected, trying reconnect for ${host.ip}")
            val ok = try { connect() } catch (e: Exception) {
                Log.e(TAG, "reconnect failed for ${host.ip}: ${e.message}", e)
                false
            }
            if (!ok) {
                Log.w(TAG, "ensureConnectedAndSendKey: reconnect failed for ${host.ip}")
                return false
            }
            Log.d(TAG, "ensureConnectedAndSendKey: reconnect success for ${host.ip}")
        }
        return sendKey(content)
    }

    suspend fun ensureConnectedAndSendJson(jsonData: Map<String, Any>): Boolean {
        if (webSocket == null || !host.connected) {
            Log.w(TAG, "ensureConnectedAndSendJson: not connected, trying reconnect for ${host.ip}")
            val ok = try { connect() } catch (e: Exception) {
                Log.e(TAG, "reconnect failed for ${host.ip}: ${e.message}", e)
                false
            }
            if (!ok) {
                Log.w(TAG, "ensureConnectedAndSendJson: reconnect failed for ${host.ip}")
                return false
            }
            Log.d(TAG, "ensureConnectedAndSendJson: reconnect success for ${host.ip}")
        }
        return sendJson(jsonData)
    }

    fun sendJson(jsonData: Map<String, Any>): Boolean {
        if (webSocket == null || !host.connected) {
            Log.w(TAG, "sendJson failed: WebSocket not connected for ${host.ip}")
            return false
        }
        
        try {
            // 将Map转换为JSON字符串
            val jsonObject = JSONObject()
            jsonData.forEach { (key, value) ->
                when (value) {
                    is String -> jsonObject.put(key, value)
                    is Int -> jsonObject.put(key, value)
                    is Boolean -> jsonObject.put(key, value)
                    is Double -> jsonObject.put(key, value)
                    is Float -> jsonObject.put(key, value)
                    is Long -> jsonObject.put(key, value)
                    else -> jsonObject.put(key, value.toString())
                }
            }
            
            val payload = jsonObject.toString()
            val sent = webSocket?.send(payload) == true
            Log.d(TAG, "sendJson to ${host.ip}, payload=$payload, sent=$sent")
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "sendJson failed: Error creating JSON for ${host.ip}: ${e.message}", e)
            return false
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting ${host.ip}")
        try {
            webSocket?.close(1000, "bye")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket for ${host.ip}: ${e.message}", e)
        }
        host.connected = false
        webSocket = null
    }
    
    // 获取host id，用于语音消息发送
    fun getHostId(): String {
        return host.id ?: initialId
    }
}