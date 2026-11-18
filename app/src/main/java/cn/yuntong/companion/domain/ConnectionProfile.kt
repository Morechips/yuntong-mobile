package cn.yuntong.companion.domain

import cn.yuntong.companion.transport.WebSocketDeviceLink
import cn.yuntong.companion.transport.DevicePeer
import org.json.JSONObject

data class ConnectionProfile(val name: String, val address: String, val token: String) {
    companion object {
        fun parse(text: String): ConnectionProfile {
            require(text.toByteArray(Charsets.UTF_8).size <= 16384) { "连接文件过大，请选择云瞳连接文件" }
            val json =
                try {
                    JSONObject(text)
                } catch (_: Exception) {
                    error("连接文件格式无法读取")
                }
            val schema = json.optString("schema")
            require(schema in setOf("yuntong-test-connection-v1", "yuntong-rdk-connection-v1")) {
                "不是支持的云瞳连接文件"
            }
            val name = json.optString("name").trim()
            val address = json.optString("address").trim()
            val token = json.optString("token").trim()
            require(name.isNotBlank() && name.length <= 80) { "连接文件缺少有效设备名称" }
            try {
                WebSocketDeviceLink.validate(address, token)
                require((DevicePeer.forAddress(address) == DevicePeer.RDK) ==
                    (schema == "yuntong-rdk-connection-v1"))
            } catch (_: Exception) {
                error("连接文件的地址或密钥不符合要求")
            }
            return ConnectionProfile(name, address, token)
        }
    }
}
