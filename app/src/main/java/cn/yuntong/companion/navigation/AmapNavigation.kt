package cn.yuntong.companion.navigation

import android.content.Context
import cn.yuntong.companion.domain.NavigationProvider
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject

data class Place(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

class AmapNavigation(
    private val context: Context,
    private val event: (String, JSONObject) -> Unit,
) : NavigationProvider {
    private var engine: AMapNavi? = null
    private var routeOnly = true
    private var generation = 0
    private var searchGeneration = 0
    private var accepted = false
    private var lastRoute = ""
    private var lastText = ""
    private var lastTextAt = 0L

    fun consent() {
        ServiceSettings.updatePrivacyShow(context, true, true)
        ServiceSettings.updatePrivacyAgree(context, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyShow(context, true, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyAgree(context, true)
        accepted = true
    }

    fun search(query: String, city: String, done: (List<Place>?, String?) -> Unit) {
        check(accepted) { "请先阅读并确认地图服务说明" }
        val epoch = ++searchGeneration
        val q = PoiSearch.Query(query, "", city).apply { pageSize = 15 }
        val search = PoiSearch(context, q)
        search.setOnPoiSearchListener(
            object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    if (epoch != searchGeneration) return
                    if (code != 1000) {
                        done(null, "地点搜索失败（$code），请检查密钥和网络")
                        return
                    }
                    done(
                        result?.pois?.map {
                            Place(
                                it.poiId,
                                it.title,
                                it.snippet ?: "",
                                it.latLonPoint.latitude,
                                it.latLonPoint.longitude,
                            )
                        } ?: emptyList(),
                        null,
                    )
                }

                override fun onPoiItemSearched(item: PoiItem?, code: Int) {}
            }
        )
        search.searchPOIAsyn()
    }

    private fun init(): AMapNavi {
        engine?.let {
            return it
        }
        val n = AMapNavi.getInstance(context)
        val epoch = generation
        n.setUseInnerVoice(false)
        // The SDK exposes overloaded callbacks; a proxy handles each exact callback by name.
        val listener =
            Proxy.newProxyInstance(
                AMapNaviListener::class.java.classLoader,
                arrayOf(AMapNaviListener::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "YuntongNaviListener"
                    else -> if (epoch != generation) return@newProxyInstance null
                }
                when (method.name) {
                    "onCalculateRouteSuccess" -> {
                        val p = n.naviPath
                        if (p == null) {
                            event("error", JSONObject().put("text", "地图未返回可用路线，请重试"))
                            return@newProxyInstance null
                        }
                        val steps = JSONArray()
                        p?.steps?.forEach { s ->
                            steps.put(
                                JSONObject()
                                    .put("length", s.length)
                                    .put("time", s.time)
                                    .put("mapIcon", s.iconType)
                                    .put(
                                        "coordinates",
                                        JSONArray().apply {
                                            s.coords?.forEach { point ->
                                                put(
                                                    JSONArray()
                                                        .put(point.latitude)
                                                        .put(point.longitude)
                                                )
                                            }
                                        },
                                    )
                                    .put(
                                        "links",
                                        JSONArray().apply {
                                            s.links?.forEach { l ->
                                                put(
                                                    JSONObject()
                                                        .put("road", l.roadName ?: "")
                                                        .put("length", l.length)
                                                )
                                            }
                                        },
                                    )
                            )
                        }
                        val result =
                            JSONObject()
                                .put("meters", p?.allLength ?: 0)
                                .put("seconds", p?.allTime ?: 0)
                                .put("steps", steps)
                                .put("coordinateSystem", "GCJ-02")
                        if (result.toString() != lastRoute) {
                            lastRoute = result.toString()
                            event("route", result)
                        }
                    }
                    "onCalculateRouteFailure" ->
                        event("error", JSONObject().put("text", "路线规划失败，请检查网络、定位和服务密钥"))
                    "onNaviInfoUpdate" -> {
                        val i = args?.firstOrNull() as? NaviInfo
                        if (i != null)
                            event(
                                "progress",
                                JSONObject()
                                    .put("road", i.currentRoadName ?: "")
                                    .put("nextRoad", i.nextRoadName ?: "")
                                    .put("meters", i.pathRetainDistance)
                                    .put("seconds", i.pathRetainTime)
                                    .put("stepMeters", i.curStepRetainDistance)
                                    .put("icon", i.iconType),
                            )
                    }
                    "onGetNavigationText" -> {
                        val text = args?.filterIsInstance<String>()?.firstOrNull()
                        val now = System.currentTimeMillis()
                        if (text != null && (text != lastText || now - lastTextAt > 1500)) {
                            lastText = text
                            lastTextAt = now
                            event("instruction", JSONObject().put("text", text))
                        }
                    }
                    "onLocationChange" -> {
                        val l = args?.firstOrNull() as? AMapNaviLocation
                        if (l?.coord != null)
                            event(
                                "location",
                                JSONObject()
                                    .put("lat", l.coord.latitude)
                                    .put("lon", l.coord.longitude)
                                    .put("accuracy", l.accuracy)
                                    .put("sdkTime", l.time)
                                    .put("mapBearingDegrees", l.bearing)
                                    .put("coordinateSystem", "GCJ-02"),
                            )
                    }
                    "onGpsSignalWeak" ->
                        if (args?.firstOrNull() != false) event("weak", JSONObject())
                    "onArriveDestination" -> event("nearby", JSONObject())
                    "onReCalculateRouteForYaw" -> {
                        lastRoute = ""
                        event("recalculating", JSONObject())
                    }
                }
                null
            } as AMapNaviListener
        n.addAMapNaviListener(listener)
        engine = n
        return n
    }

    fun plan(place: Place, latitude: Double?, longitude: Double?) {
        check(accepted)
        close()
        lastRoute = ""
        val n = init()
        routeOnly = true
        val target = NaviLatLng(place.latitude, place.longitude)
        val started =
            if (latitude != null && longitude != null)
                n.calculateWalkRoute(NaviLatLng(latitude, longitude), target)
            else n.calculateWalkRoute(target)
        check(started) { "路线规划未能启动，请检查定位权限和网络" }
    }

    fun start() {
        routeOnly = false
        check(init().startNavi(1)) { "导航未能启动" }
    }

    override fun pause() {
        engine?.pauseNavi()
    }

    override fun resume() {
        engine?.resumeNavi()
    }

    override fun stop() {
        generation++
        engine?.stopNavi()
    }

    fun replan() {
        engine?.reCalculateRoute(0)
    }

    fun close() {
        stop()
        AMapNavi.destroy()
        engine = null
    }
}
