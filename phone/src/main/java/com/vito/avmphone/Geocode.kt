package com.vito.avmphone

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 搜尋目的地：ArcGIS（台灣門牌地址準，例如「新竹市東區金山二十街28號」）＋ OpenStreetMap Nominatim（店名、地標）
 * 一起查、合併（同一個地點 50 m 內只留一個）。地址帶「號／巷／弄」兩邊都找不到 → 去掉門牌再查整條路。
 */
object Geocode {
    data class Place(val name: String, val addr: String, val lat: Double, val lon: Double)

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "geo").apply { isDaemon = true } }
    private const val UA = "E60Phone/1.0 (personal car companion)"

    /** 回傳結果與「是否退到整條路」。網路全失敗丟例外。 */
    fun search(q: String, lat: Double?, lon: Double?): Pair<List<Place>, Boolean> {
        val r = once(q, lat, lon)
        if (r.isNotEmpty()) return r to false
        val street = q.replace(Regex("\\d+(之\\d+)?號.*$"), "").replace(Regex("\\d+(巷|弄).*$"), "").trim()
        if (street.isNotEmpty() && street != q) return once(street, lat, lon) to true
        return r to false
    }

    private fun once(q: String, lat: Double?, lon: Double?): List<Place> {
        val fa = pool.submit(Callable { arc(q, lat, lon) })
        val fo = pool.submit(Callable { osm(q, lat, lon) })
        var err: Throwable? = null
        val a = try { fa.get(10, TimeUnit.SECONDS) } catch (t: Throwable) { err = t; emptyList() }
        val o = try { fo.get(10, TimeUnit.SECONDS) } catch (t: Throwable) { err = t; emptyList() }
        val out = ArrayList<Place>()
        for (p in a + o) if (out.none { Math.abs(it.lat - p.lat) < 0.0005 && Math.abs(it.lon - p.lon) < 0.0005 }) out += p
        if (out.isEmpty() && a.isEmpty() && o.isEmpty() && err != null && fa.isDone && fo.isDone) {
            // 兩邊都是例外（不是查無結果）才算失敗
            val bothFailed = runCatching { fa.get() }.isFailure && runCatching { fo.get() }.isFailure
            if (bothFailed) throw err
        }
        return out
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", UA)
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun arc(q: String, lat: Double?, lon: Double?): List<Place> {
        var u = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates?f=json&maxLocations=5" +
            "&countryCode=TWN&outFields=PlaceName,Place_addr&SingleLine=" + enc(q)
        if (lat != null && lon != null) u += "&location=$lon,$lat"
        val arr = JSONObject(get(u)).optJSONArray("candidates") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i); val loc = c.getJSONObject("location")
            val y = loc.getDouble("y"); val x = loc.getDouble("x")
            if (c.optDouble("score") < 80 || y !in 21.5..26.5 || x !in 118.0..122.5) continue   // 只要台灣、夠像的
            val at = c.optJSONObject("attributes")
            val addr = c.optString("address")
            out += Place(at?.optString("PlaceName")?.takeIf { it.isNotEmpty() } ?: addr.substringBefore(','),
                at?.optString("Place_addr")?.takeIf { it.isNotEmpty() } ?: addr, y, x)
        }
        return out
    }

    private fun osm(q: String, lat: Double?, lon: Double?): List<Place> {
        var u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&accept-language=zh-TW&countrycodes=tw&q=" + enc(q)
        if (lat != null && lon != null) u += "&viewbox=${lon - 0.5},${lat + 0.5},${lon + 0.5},${lat - 0.5}"
        val arr = JSONArray(get(u))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val dn = o.optString("display_name")
            out += Place(o.optString("name").ifEmpty { dn.substringBefore(',') }, dn, o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
        return out
    }
}
