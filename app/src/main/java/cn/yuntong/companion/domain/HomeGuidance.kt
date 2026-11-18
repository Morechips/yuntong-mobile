package cn.yuntong.companion.domain

/** Display priority only. Does not interpret obstacles or create movement decisions. */
data class HomeGuidance(val text: String, val detail: String, val available: Boolean = false)

fun homeGuidance(
    navigation: NavigationState,
    cooperative: Boolean,
    connected: Boolean,
    guidance: String,
    expired: Boolean,
    navigationIssue: String,
    realDevice: Boolean = false,
): HomeGuidance =
    when {
        navigation.phase !in setOf("active", "paused") -> HomeGuidance("尚未开始导航", "选择目的地后预习路线")
        navigation.phase == "paused" -> HomeGuidance("导航已暂停", "恢复后等待新的提示")
        cooperative && !connected -> HomeGuidance("设备连接已断开", "协同提示不可用，请重新连接设备")
        navigationIssue.isNotBlank() -> HomeGuidance(navigationIssue, "等待导航服务恢复，请确认周围环境")
        cooperative && guidance.isNotBlank() ->
            HomeGuidance(guidance, if (realDevice) "云瞳眼镜 · 台架感知提示" else "电脑测试端返回 · 模拟助行提示", true)
        cooperative && expired -> HomeGuidance("设备提示已过期", "等待设备返回新的有效提示")
        cooperative -> HomeGuidance("等待设备提示", "导航信息由手机发送，助行提示由设备返回")
        navigation.instruction.isNotBlank() ->
            HomeGuidance(
                navigation.instruction,
                if (navigation.source == "simulation") "路线演练 · 模拟导航提示" else "高德步行导航提示",
                true,
            )
        else -> HomeGuidance("等待导航更新", "获取新提示后将在这里显示")
    }
