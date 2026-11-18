package cn.yuntong.companion.transport

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

interface DeviceLink {
    fun connect(address: String, token: String)

    fun send(value: JSONObject): Boolean

    fun close()
}

enum class DevicePeer(val schema: String, val connectedLabel: String) {
    TEST("yuntong-link-test-v1", "已连接测试接收端"),
    RDK("yuntong-rdk-link-v1", "已连接云瞳眼镜");

    companion object {
        fun forAddress(address: String): DevicePeer =
            if (URI(address).path == "/rdk") RDK else TEST
    }
}

class WebSocketDeviceLink(
    private val scope: CoroutineScope,
    private val state: (String) -> Unit,
    private val message: (JSONObject) -> Unit,
) : DeviceLink {
    private val client =
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var active = false
    @Volatile private var ready = false
    private var retry: Job? = null
    private var endpoint = ""
    private var token = ""
    private var peer = DevicePeer.TEST

    companion object {
        fun validate(address: String, token: String) {
            val uri = URI(address)
            require(
                uri.scheme in setOf("ws", "wss") &&
                    uri.host != null &&
                    uri.userInfo == null &&
                    uri.fragment == null &&
                    uri.query == null
            ) {
                "请输入有效的 WebSocket 地址"
            }
            if (uri.scheme == "ws") {
                val ip = uri.host.split('.').mapNotNull { it.toIntOrNull() }
                require(
                    uri.host == "localhost" ||
                        ip.size == 4 &&
                            ip.all { it in 0..255 } &&
                            (ip[0] == 127 ||
                                ip[0] == 10 ||
                                ip[0] == 192 && ip[1] == 168 ||
                                ip[0] == 172 && ip[1] in 16..31)
                ) {
                    "明文连接仅允许局域网地址"
                }
            }
            require(token.length in 16..128) { "连接密钥需16至128位" }
            require(token.all { it in '!'..'~' }) { "连接密钥应为无空格的英文字符" }
        }
    }

    @Synchronized
    override fun connect(address: String, token: String) {
        validate(address, token)
        close()
        endpoint = address
        this.token = token
        peer = DevicePeer.forAddress(address)
        active = true
        open()
    }

    @Synchronized
    private fun open() {
        val epoch = ++generation
        val expectedPeer = peer
        ready = false
        state("连接中")
        socket =
            client.newWebSocket(
                Request.Builder().url(endpoint).header("Authorization", "Bearer $token").build(),
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        if (epoch != generation) {
                            ws.close(1000, null)
                            return
                        }
                        ws.send(
                            JSONObject()
                                .put("schema", expectedPeer.schema)
                                .put("type", "hello")
                                .put("client", "yuntong-android")
                                .toString()
                        )
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        if (epoch != generation || text.length > 4_000_000) return
                        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                        if (obj.optString("schema") != expectedPeer.schema) return
                        scope.launch {
                            if (epoch != generation) return@launch
                            if (obj.optString("type") == "hello.accepted") {
                                if (ready) return@launch
                                val caps = obj.optJSONArray("capabilities")
                                val advertised = caps?.let { array ->
                                    (0 until array.length()).map { array.optString(it) }.toSet()
                                }.orEmpty()
                                val required = if (expectedPeer == DevicePeer.RDK)
                                    setOf("navigation.receive", "device.status", "device.command")
                                else setOf("navigation.receive")
                                if (obj.opt("testOnly") != (expectedPeer == DevicePeer.TEST) ||
                                    !advertised.containsAll(required)
                                ) {
                                    ws.close(1008, "unexpected capabilities")
                                    state("设备协议或能力不匹配")
                                    return@launch
                                }
                                ready = true
                                state(expectedPeer.connectedLabel)
                            }
                            if (ready) message(obj)
                        }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        lost(epoch)
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        lost(epoch)
                    }

                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        ws.close(code, reason)
                        lost(epoch)
                    }
                },
            )
        scope.launch {
            delay(10_000)
            if (epoch == generation && !ready) {
                socket?.cancel()
                lost(epoch)
            }
        }
    }

    private fun lost(epoch: Int) {
        scope.launch {
            synchronized(this@WebSocketDeviceLink) {
                if (epoch != generation || !active) return@launch
                generation++
                ready = false
                state("连接中断，等待重连")
                retry?.cancel()
                retry =
                    scope.launch {
                        delay(3000)
                        synchronized(this@WebSocketDeviceLink) { if (active) open() }
                    }
            }
        }
    }

    @Synchronized
    override fun send(value: JSONObject): Boolean {
        val connection = socket ?: return false
        if (!ready || connection.queueSize() >= 512_000) return false
        val envelope = JSONObject(value.toString()).put("schema", peer.schema)
        return connection.send(envelope.toString())
    }

    @Synchronized
    override fun close() {
        active = false
        ready = false
        generation++
        retry?.cancel()
        socket?.close(1000, "user closed")
        socket = null
        state("未连接")
    }
}
