package cn.yuntong.companion.domain

/** Main-thread state for a single request, including the asynchronous decode phase. */
internal class MediaCapture {
    var requestId: String? = null
        private set
    private var decoding = false

    fun begin(id: String): Boolean {
        if (requestId != null) return false
        requestId = id
        decoding = false
        return true
    }

    fun claimFrame(id: String): Boolean {
        if (id != requestId || decoding) return false
        decoding = true
        return true
    }

    fun finish(id: String): Boolean {
        if (id != requestId) return false
        cancel()
        return true
    }

    fun cancel() {
        requestId = null
        decoding = false
    }
}
