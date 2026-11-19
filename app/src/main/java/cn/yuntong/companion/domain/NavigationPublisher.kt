package cn.yuntong.companion.domain

import java.util.UUID
import org.json.JSONObject

data class NavigationState(
    val sessionId: String = "",
    val routeVersion: Int = 0,
    val phase: String = "idle",
    val source: String = "amap",
    val destination: String = "",
    val instruction: String = "",
    val remainingMeters: Int? = null,
    val remainingSeconds: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val coordinateSystem: String = "GCJ-02",
    val route: String = "{}",
    val locationAt: Long? = null,
    val instructionAt: Long? = null,
)

/** Owns publishing metadata, never obstacle fusion or actuator decisions. */
class NavigationPublisher(private val clock: () -> Long = System::currentTimeMillis) {
    var state = NavigationState()
        private set

    private var sequence = 0L
    private var routeGeneration = 0
    private var lastLocationTime: Long? = null
    private val sent = linkedMapOf<Long, Pair<String, Int>>()
    private var lastResultSequence = 0L
    private val issuedAt = linkedMapOf<Long, Long>()

    fun begin(destination: String, source: String): JSONObject {
        require(source in setOf("amap", "simulation"))
        state =
            NavigationState(
                sessionId = UUID.randomUUID().toString(),
                routeVersion = 1,
                phase = "active",
                source = source,
                destination = destination,
            )
        sequence = 0
        routeGeneration = 1
        lastLocationTime = null
        sent.clear()
        issuedAt.clear()
        lastResultSequence = 0
        return envelope("session.start")
    }

    fun newRoute(route: JSONObject): JSONObject {
        check(state.phase in setOf("active", "paused"))
        state =
            state.copy(
                routeVersion = ++routeGeneration,
                route = route.toString(),
                instruction = "",
                instructionAt = null,
                remainingMeters = null,
                remainingSeconds = null,
            )
        sent.clear()
        issuedAt.clear()
        return envelope("route.replace")
    }

    fun progress(instruction: String, meters: Int?, seconds: Int?): JSONObject? {
        if (state.phase != "active") return null
        state =
            state.copy(
                instruction = instruction,
                remainingMeters = meters,
                remainingSeconds = seconds,
                instructionAt =
                    if (instruction != state.instruction) clock() else state.instructionAt,
            )
        return envelope("navigation.progress")
    }

    fun location(lat: Double, lon: Double, accuracy: Float, coord: String): JSONObject? {
        if (
            state.phase !in setOf("active", "paused") ||
                !lat.isFinite() ||
                !lon.isFinite() ||
                lat !in -90.0..90.0 ||
                lon !in -180.0..180.0 ||
                !accuracy.isFinite() ||
                accuracy < 0
        )
            return null
        state =
            state.copy(
                latitude = lat,
                longitude = lon,
                accuracy = accuracy,
                coordinateSystem = coord,
                locationAt = clock(),
            )
        val now = clock()
        if (lastLocationTime?.let { now - it in 0L..999L } == true) return null
        lastLocationTime = now
        return envelope("navigation.location")
    }

    fun transition(phase: String): JSONObject {
        require(phase in setOf("paused", "active", "ended"))
        check(state.sessionId.isNotEmpty() && state.phase != "ended")
        state = state.copy(phase = phase, instruction = "")
        if (phase != "paused") invalidateLocation()
        sent.clear()
        issuedAt.clear()
        lastResultSequence = sequence
        return envelope("session.$phase")
    }

    fun invalidateLocation() {
        state = state.copy(latitude = null, longitude = null, accuracy = null, locationAt = null)
        lastLocationTime = null
    }

    fun snapshot(): JSONObject? =
        if (state.sessionId.isEmpty()) null else envelope("navigation.snapshot")

    fun action(action: String): JSONObject? =
        if (state.phase !in setOf("active", "paused")) null
        else envelope("user.action").put("action", action)

    private fun envelope(type: String): JSONObject {
        val seq = ++sequence
        sent[seq] = state.sessionId to state.routeVersion
        issuedAt[seq] = clock()
        while (sent.size > 64) {
            val first = sent.keys.first()
            sent.remove(first)
            issuedAt.remove(first)
        }
        return JSONObject()
            .put("schema", "yuntong-link-test-v1")
            .put("type", type)
            .put("sessionId", state.sessionId)
            .put("routeVersion", state.routeVersion)
            .put("sequence", seq)
            .put("sentAt", clock())
            .put("source", state.source)
            .put("phase", state.phase)
            .put("destination", state.destination)
            .apply {
                if (type in setOf("route.replace", "navigation.snapshot", "session.start"))
                    put("route", JSONObject(state.route))
            }
            .put(
                "instruction",
                if (type == "navigation.snapshot" && clock() - (state.instructionAt ?: 0) > 5000) ""
                else state.instruction,
            )
            .put("instructionAt", state.instructionAt ?: JSONObject.NULL)
            .put("remainingMeters", state.remainingMeters ?: JSONObject.NULL)
            .put("remainingSeconds", state.remainingSeconds ?: JSONObject.NULL)
            .put(
                "location",
                JSONObject()
                    .put("latitude", state.latitude ?: JSONObject.NULL)
                    .put("longitude", state.longitude ?: JSONObject.NULL)
                    .put("accuracyMeters", state.accuracy ?: JSONObject.NULL)
                    .put("sampledAt", state.locationAt ?: JSONObject.NULL)
                    .put("coordinateSystem", state.coordinateSystem)
                    .put("headingReference", "phone-location-not-head"),
            )
    }

    fun acceptsReply(message: JSONObject, guidance: Boolean = false): Boolean {
        val seq = message.optLong("sequence", -1)
        if (
            message.optString("sessionId") != state.sessionId ||
                message.optString("source") != state.source ||
                message.optInt("routeVersion") != state.routeVersion ||
                sent[seq] != (state.sessionId to state.routeVersion)
        )
            return false
        if (guidance) {
            if (
                state.phase != "active" ||
                    seq <= lastResultSequence ||
                    message.optString("source") != state.source ||
                    clock() - (issuedAt[seq] ?: 0) > 5000
            )
                return false
            lastResultSequence = seq
        }
        return true
    }

    fun forgetReplies() {
        sent.clear()
        issuedAt.clear()
        lastResultSequence = sequence
    }
}
