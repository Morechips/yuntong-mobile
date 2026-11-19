package cn.yuntong.companion.transport

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RdkMediaLinkTest {
    @Test fun captureRequiresOpenMediaAndPreviewReturnsAfterServerClose() = runBlocking {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val commands = Channel<JSONObject>(Channel.UNLIMITED)
        val connected = Channel<Boolean>(Channel.UNLIMITED)
        val received = Channel<JSONObject>(Channel.UNLIMITED)
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { sockets.offer(ws) }
                override fun onMessage(ws: WebSocket, text: String) { commands.trySend(JSONObject(text)) }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            }))
        }
        server.start()
        val link = RdkMediaLink(this, { received.trySend(it) }, { connected.trySend(it) })
        try {
            link.connect(server.url("/rdk").toString().replace("http:", "ws:"), "fixture-only-token")
            assertFalse(link.capture("before-open"))
            link.subscribe(true)
            link.resume()
            assertTrue(withTimeout(5000) { connected.receive() })
            assertEquals("/rdk/media", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertTrue(withTimeout(3000) { commands.receive() }.getBoolean("enabled"))
            assertTrue(link.capture("capture-1"))
            assertEquals("capture-1", withTimeout(3000) { commands.receive() }.getString("requestId"))
            val socket = sockets.remove()
            socket.send(JSONObject().put("schema", DevicePeer.RDK.schema).put("type", "media.unavailable")
                .put("requestId", "capture-1").toString())
            assertEquals("media.unavailable", withTimeout(3000) { received.receive() }.getString("type"))
            socket.close(1012, "test restart")
            assertFalse(withTimeout(4000) { connected.receive() })
            assertFalse(link.capture("during-reconnect"))
            assertTrue(withTimeout(7000) { connected.receive() })
            assertTrue(withTimeout(3000) { commands.receive() }.getBoolean("enabled"))
        } finally {
            link.close()
            server.shutdown()
        }
    }
}
