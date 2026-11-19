package cn.yuntong.companion.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationPublisherTest {
    @Test fun resumeAndLocationLossDropCachedCoordinates() {
        var now = 20_000L
        val publisher = NavigationPublisher { now }
        publisher.begin("test destination", "amap")
        assertNotNull(publisher.location(30.0, 120.0, 12f, "GCJ-02"))
        publisher.transition("paused")
        publisher.transition("active")
        assertNull(publisher.state.locationAt)
        val position = publisher.snapshot()!!.getJSONObject("location")
        assertTrue(position.isNull("latitude"))
        assertTrue(position.isNull("longitude"))
        assertTrue(position.isNull("sampledAt"))
        now += 20
        assertNotNull(publisher.location(30.0, 120.0, 10f, "GCJ-02"))
        publisher.invalidateLocation()
        assertNull(publisher.state.latitude)
        assertNotNull(publisher.location(30.0, 120.0, 8f, "GCJ-02"))
    }
    private fun reply(p: JSONObject) = JSONObject(p.toString())

    @Test
    fun oldRouteCannotOverwriteCurrent() {
        val p = NavigationPublisher()
        p.begin("A", "simulation")
        val old = p.progress("left", 100, 60)!!
        p.newRoute(JSONObject())
        assertFalse(p.acceptsReply(reply(old), true))
    }

    @Test
    fun endAndPauseInvalidateGuidance() {
        val p = NavigationPublisher()
        p.begin("A", "amap")
        val old = p.progress("left", 100, 60)!!
        p.transition("paused")
        assertFalse(p.acceptsReply(old, true))
        p.transition("ended")
        assertNull(p.progress("left", 1, 1))
        assertNull(p.location(30.0, 120.0, 5f, "GCJ-02"))
    }

    @Test
    fun deduplicateAndOrder() {
        val p = NavigationPublisher()
        p.begin("A", "simulation")
        val a = p.progress("a", 2, 2)!!
        val b = p.progress("b", 1, 1)!!
        assertTrue(p.acceptsReply(b, true))
        assertFalse(p.acceptsReply(a, true))
        assertFalse(p.acceptsReply(b, true))
    }

    @Test
    fun expiryUsesLocalClock() {
        var now = 10000L
        val p = NavigationPublisher { now }
        p.begin("A", "amap")
        val a = p.progress("a", 1, 1)!!
        now += 5001
        assertFalse(p.acceptsReply(a, true))
    }

    @Test
    fun sourceIsolation() {
        val p = NavigationPublisher()
        p.begin("A", "amap")
        val a = p.progress("a", 1, 1)!!
        a.put("source", "simulation")
        assertFalse(p.acceptsReply(a, true))
    }

    @Test
    fun reconnectOnlyCurrentSnapshot() {
        val p = NavigationPublisher()
        p.begin("A", "simulation")
        val a = p.progress("a", 1, 1)!!
        p.forgetReplies()
        assertFalse(p.acceptsReply(a, true))
        val current = p.snapshot()!!
        assertTrue(p.acceptsReply(current, true))
        p.transition("ended")
        assertEquals("ended", p.snapshot()!!.getString("phase"))
    }

    @Test
    fun coalesceLocationKeepsLatest() {
        var now = 10000L
        val p = NavigationPublisher { now }
        p.begin("A", "amap")
        assertNotNull(p.location(30.0, 120.0, 5f, "GCJ-02"))
        now += 100
        assertNull(p.location(31.0, 120.0, 5f, "GCJ-02"))
        assertEquals(31.0, p.snapshot()!!.getJSONObject("location").getDouble("latitude"), 0.0)
    }

    @Test
    fun sessionReplacementAndVersionReset() {
        val p = NavigationPublisher()
        val old = p.begin("A", "simulation")
        p.newRoute(JSONObject())
        p.begin("B", "amap")
        assertEquals(1, p.state.routeVersion)
        assertFalse(p.acceptsReply(old))
        assertEquals(2, p.newRoute(JSONObject()).getInt("routeVersion"))
    }

    @Test
    fun invalidLocationsRejected() {
        val p = NavigationPublisher()
        p.begin("A", "amap")
        assertNull(p.location(Double.NaN, 0.0, 1f, "GCJ-02"))
        assertNull(p.location(91.0, 0.0, 1f, "GCJ-02"))
        assertNull(p.location(30.0, 0.0, -1f, "GCJ-02"))
    }

    @Test
    fun reconnectDoesNotReplayExpiredTurn() {
        var now = 10000L
        val p = NavigationPublisher { now }
        p.begin("A", "amap")
        p.progress("向左转", 10, 10)
        now += 6000
        p.progress("向左转", 8, 8)
        assertEquals("", p.snapshot()!!.getString("instruction"))
    }
}
