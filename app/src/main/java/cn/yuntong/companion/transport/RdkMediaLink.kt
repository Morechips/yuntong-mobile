package cn.yuntong.companion.transport

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class RdkMediaLink(
    private val scope: CoroutineScope,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var address = ""
    private var token = ""
    private var active = false
    private var ready = false
    private var subscribed = false
    private var generation = 0

    fun connect(controlAddress: String, token: String) {
        close()
        val uri = URI(controlAddress)
        address = URI(uri.scheme, null, uri.host, uri.port, "/rdk/media", null, null).toString()
        this.token = token
    }

    fun pause() {
        active = false
        ready = false
        generation++
        socket?.cancel()
        socket = null
    }

    fun resume() {
        if (!active && address.isNotEmpty()) {
            active = true
            open()
        }
    }

    private fun open() {
        val epoch = ++generation
        socket = client.newWebSocket(
            Request.Builder().url(address).header("Authorization", "Bearer $token").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    scope.launch {
                        if (epoch != generation) return@launch
                        ready = true
                        if (subscribed) ws.send(command("media.subscribe").put("enabled", true).toString())
                        onConnection(true)
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.length > 1_500_000) return
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (message.optString("schema") == DevicePeer.RDK.schema)
                        scope.launch { if (epoch == generation) onMessage(message) }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = retry(epoch)
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                    retry(epoch)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = retry(epoch)
            },
        )
    }

    private fun retry(epoch: Int) {
        scope.launch {
            if (!active || epoch != generation) return@launch
            val retryGeneration = ++generation
            ready = false
            socket?.cancel()
            socket = null
            onConnection(false)
            delay(3000)
            if (active && retryGeneration == generation) open()
        }
    }

    fun subscribe(enabled: Boolean) {
        subscribed = enabled
        if (ready) socket?.send(command("media.subscribe").put("enabled", enabled).toString())
    }

    fun capture(requestId: String): Boolean =
        ready && socket?.send(command("media.capture").put("requestId", requestId).toString()) == true

    fun close() {
        pause()
        subscribed = false
        address = ""
        token = ""
    }

    private fun command(type: String) = JSONObject().put("schema", DevicePeer.RDK.schema).put("type", type)
}
