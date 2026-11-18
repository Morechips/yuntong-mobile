package cn.yuntong.companion.domain

fun manualEmergencyMessage(location: String?): String =
    "【云瞳紧急求助】我现在需要帮助，请尽快给我打电话。\n" +
        (location ?: "当前位置暂不可用，请联系我确认位置。")

fun fallEmergencyMessage(coordinates: String?): String =
    "【云瞳跌倒求助】眼镜报告我发生跌倒，我可能需要帮助，请尽快给我打电话。\n" +
        (coordinates?.let { "位置：$it" } ?: "当前位置暂不可用，请联系我确认位置。")
