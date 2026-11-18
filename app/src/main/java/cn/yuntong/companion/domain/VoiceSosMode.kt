package cn.yuntong.companion.domain

enum class VoiceSosMode { PRACTICE, LIVE }
enum class SosAction { CALL_CONTACT, REQUEST_SMS }

/** Practice must never dispatch an external communication action. */
fun sosActions(mode: VoiceSosMode): List<SosAction> = when (mode) {
    VoiceSosMode.PRACTICE -> emptyList()
    VoiceSosMode.LIVE -> listOf(SosAction.CALL_CONTACT, SosAction.REQUEST_SMS)
}
