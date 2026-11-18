package cn.yuntong.companion.transport

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceLinkTest {
    @Test fun rdkHandshakeUsesSeparateProtocolForDeviceCommands() {
        val received = LinkedBlockingQueue<JSONObject>()
        val ready = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val message = JSONObject(text)
                received.offer(message)
                if (message.optString("type") == "hello") {
                    ws.send(JSONObject().put("schema", DevicePeer.RDK.schema)
                        .put("type", "hello.accepted").put("testOnly", false)
                        .put("capabilities", JSONArray(listOf("navigation.receive", "device.status", "device.command")))
                        .toString())
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }
        }))
        server.start()
        val link = WebSocketDeviceLink(scope, { if (it == DevicePeer.RDK.connectedLabel) ready.offer(it) }, {})
        try {
            link.connect(server.url("/rdk").toString().replace("http:", "ws:"), "test-token-for-unit")
            assertNotNull(ready.poll(5, TimeUnit.SECONDS))
            assertEquals(DevicePeer.RDK.schema, received.poll(2, TimeUnit.SECONDS)?.optString("schema"))
            assertTrue(link.send(JSONObject().put("type", "device.command").put("action", "status")))
            val command = received.poll(2, TimeUnit.SECONDS)!!
            assertEquals(DevicePeer.RDK.schema, command.getString("schema"))
            assertEquals("status", command.getString("action"))
        } finally {
            link.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun serverCloseTriggersFreshHandshake() {
        val sockets=LinkedBlockingQueue<WebSocket>()
        val ready=LinkedBlockingQueue<String>()
        val received=LinkedBlockingQueue<String>()
        val server=MockWebServer()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        repeat(2){server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(ws:WebSocket,response:Response){sockets.offer(ws)}
            override fun onMessage(ws:WebSocket,text:String){
                val m=JSONObject(text)
                if(m.optString("type")=="hello")ws.send(JSONObject().put("schema","yuntong-link-test-v1").put("type","hello.accepted").put("testOnly",true).put("capabilities",JSONArray().put("navigation.receive")).toString())
                else received.offer(text)
            }
            override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
        }))}
        server.start()
        val link=WebSocketDeviceLink(scope,{if(it=="已连接测试接收端")ready.offer(it)},{})
        try{
            link.connect(server.url("/link").toString().replace("http:","ws:"),"test-token-for-unit")
            assertNotNull(ready.poll(5,TimeUnit.SECONDS))
            assertEquals("Bearer test-token-for-unit",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            sockets.poll(2,TimeUnit.SECONDS)!!.close(1012,"test restart")
            assertNotNull(ready.poll(9,TimeUnit.SECONDS))
            assertTrue(link.send(JSONObject().put("type","probe")))
            assertEquals("probe",JSONObject(received.poll(3,TimeUnit.SECONDS)!!).getString("type"))
        }finally{link.close();scope.cancel();server.shutdown()}
    }
}
