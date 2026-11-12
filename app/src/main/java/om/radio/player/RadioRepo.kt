package om.radio.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

data class Variant(val url: String, val bitrate: Int, val codec: String, val hls: Boolean)

data class Station(
    val key: String,
    val name: String,
    val ar: String,
    val votes: Int,
    val variants: List<Variant>,
    val custom: Boolean = false,
    val intl: Boolean = false,
) {
    val kbps: Int get() = variants.firstOrNull()?.bitrate ?: 0
    val title: String get() = ar.ifBlank { latin.ifBlank { name } }
    val subtitle: String get() = if (ar.isNotBlank()) latin else ""
    private val latin: String get() = latinPart(name)
    val monogram: String
        get() {
            val word = title.split(" ").firstOrNull { it.isNotBlank() && it !in GENERIC } ?: title
            return word.removePrefix("ال").trim().firstOrNull()?.uppercase() ?: "?"
        }

    companion object {
        private val arabic = Regex("[\\u0600-\\u06FF][\\u0600-\\u06FF\\s.\\-]*[\\u0600-\\u06FF]")
        private val GENERIC = setOf("إذاعة", "اذاعة", "راديو", "Radio", "radio")
        // Directory names often use dashes/underscores as spaces ("إذاعة-القرآن-الكريم", "-- Quran Program").
        private fun tidy(t: String) = t.replace(Regex("[-_.|]+"), " ").replace(Regex("\\s+"), " ")
            .trim { !it.isLetterOrDigit() && it != ')' }
        fun arabicPart(n: String) = tidy(arabic.find(n)?.value ?: "")
        fun latinPart(n: String) = tidy(n.replace(Regex("\\(.*?\\)"), "").replace(Regex("[\\u0600-\\u06FF]"), ""))
        fun keyOf(n: String): String =
            latinPart(n).lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { arabicPart(n) }
    }
}

fun mbPerHour(kbps: Int): Double = kbps * 3600.0 / 8.0 / 1024.0

/** Station list, settings and data-usage estimate, shared by the screen and the player service. */
object RadioRepo {
    const val SAVER_MAX_KBPS = 64
    private const val CACHE_DAYS = 7
    private val HOSTS = listOf(
        "de1.api.radio-browser.info", "fi1.api.radio-browser.info",
        "de2.api.radio-browser.info", "nl1.api.radio-browser.info",
    )

    private const val CACHE_VERSION = 4

    /** International Arabic/news stations, found in the directory by name rather than by country. */
    private class Intl(
        val key: String, val ar: String, val en: String, val queries: List<String>,
        val match: Regex, val exclude: Regex? = null, val prefer: Regex? = null, val language: String? = null,
    )
    private val I = RegexOption.IGNORE_CASE
    private val INTL = listOf(
        // The directory spells it several ways: "Monte Carlo Doualiya", "Montecarlo al doualiya", Arabic only…
        Intl("intl-mcd", "مونت كارلو الدولية", "Monte Carlo Doualiya", listOf("doualiya", "dawliya", "مونت كارلو", "montecarlo"),
            Regex("doualiya|dawliya|مونت\\s*كارلو|monte\\s*-?\\s*carlo\\s+(al\\s+)?d", I)),
        Intl("intl-sawa", "راديو سوا", "Radio Sawa", listOf("sawa", "راديو سوا"),
            Regex("radio\\s*sawa|راديو\\s*سوا", I), prefer = Regex("gulf|khaleej|خليج", I)),
        Intl("intl-jazeera", "الجزيرة", "Al Jazeera Arabic", listOf("jazeera", "الجزيرة"),
            Regex("jazeera|الجزيرة", I), exclude = Regex("english|balkan|mubasher|مباشر|documentary|وثائقية|kids|أطفال", I)),
        Intl("intl-bbcws", "بي بي سي العالمية", "BBC World Service", listOf("bbc world service"),
            Regex("bbc\\s*world\\s*service", I), language = "english"),
    )

    private lateinit var prefs: SharedPreferences
    private var all: List<Station> = emptyList()

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations
    val saver = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val usageMb = MutableStateFlow(0.0)
    /** Epoch millis at which playback stops, or 0 for no timer. */
    val sleepAt = MutableStateFlow(0L)

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        saver.value = prefs.getBoolean("saver", false)
        all = prefs.getString("stations", null)?.let { parseCache(it) } ?: emptyList()
        usageMb.value = prefs.getFloat(monthKey(), 0f).toDouble()
        publish()
    }

    fun needsRefresh(): Boolean =
        all.isEmpty() || prefs.getInt("cache_v", 1) < CACHE_VERSION || System.currentTimeMillis() - prefs.getLong("stations_t", 0) > CACHE_DAYS * 86_400_000L

    var currentKey: String?
        get() = prefs.getString("cur", null)
        set(v) { prefs.edit().putString("cur", v).apply() }

    fun setSaver(on: Boolean) {
        saver.value = on
        prefs.edit().putBoolean("saver", on).apply()
        publish()
    }

    fun addCustom(name: String, url: String, kbps: Int) {
        val list = customStations().toMutableList()
        list.add(0, Station("c-${System.currentTimeMillis()}", name, Station.arabicPart(name), 0,
            listOf(Variant(url, kbps, "", url.contains(".m3u8", true))), custom = true))
        prefs.edit().putString("custom", toJson(list).toString()).apply()
        publish()
    }

    fun removeCustom(key: String) {
        val list = customStations().filterNot { it.key == key }
        prefs.edit().putString("custom", toJson(list).toString()).apply()
        publish()
    }

    fun addUsage(mb: Double) {
        val v = usageMb.value + mb
        usageMb.value = v
        prefs.edit().putFloat(monthKey(), v.toFloat()).apply()
    }

    private fun monthKey(): String {
        val c = Calendar.getInstance()
        return "usage-${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}"
    }

    private fun customStations(): List<Station> =
        prefs.getString("custom", null)?.let { parseCache(it, custom = true) } ?: emptyList()

    /** Directory entries left out of the app on purpose. */
    private val BLOCKED = listOf(Regex("^\\W*oman\\s*fm\\b", RegexOption.IGNORE_CASE))

    val hiddenCount = MutableStateFlow(0)
    private fun hiddenKeys(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()

    /** Hide a directory station (long-press in the list). */
    fun hide(key: String) {
        prefs.edit().putStringSet("hidden", hiddenKeys() + key).apply()
        publish()
    }

    fun unhideAll() {
        prefs.edit().remove("hidden").apply()
        publish()
    }

    private fun publish() {
        val hidden = hiddenKeys()
        hiddenCount.value = hidden.size
        val combined = customStations() + all.filter { s ->
            s.key !in hidden && BLOCKED.none { it.containsMatchIn(s.name) }
        }
        _stations.value = if (saver.value)
            combined.filter { it.custom || it.kbps == 0 || it.kbps <= SAVER_MAX_KBPS }
        else combined
    }

    suspend fun refresh(force: Boolean = false) {
        if (loading.value || (!force && !needsRefresh())) return
        loading.value = true
        error.value = null
        try {
            val q = "hidebroken=true&order=votes&reverse=true&limit=150"
            val byCountry = fetchJson("stations/search?countrycode=OM&$q")
            val byName = runCatching { fetchJson("stations/search?name=${URLEncoder.encode("oman", "UTF-8")}&$q") }
                .getOrDefault(JSONArray())
            val raw = mutableListOf<JSONObject>()
            for (i in 0 until byCountry.length()) raw += byCountry.getJSONObject(i)
            for (i in 0 until byName.length()) {
                val o = byName.getJSONObject(i)
                if (o.optString("countrycode") == "OM" || Regex("\\boman\\b", RegexOption.IGNORE_CASE).containsMatchIn(o.optString("name"))) raw += o
            }
            val intlFresh = INTL.mapNotNull { d -> runCatching { buildIntl(d) }.getOrNull() }
            val intl = intlFresh.ifEmpty { all.filter { it.intl } }
            // Move a station out of the Oman list only when its international entry was actually found.
            val moved = INTL.filter { d -> intl.any { it.key == d.key } }
            val oman = group(raw).filterNot { s -> moved.any { it.match.containsMatchIn(s.name) } }
            if (oman.isNotEmpty() || intl.isNotEmpty()) {
                all = oman + intl
                prefs.edit().putString("stations", toJson(all).toString())
                    .putLong("stations_t", System.currentTimeMillis())
                    .putInt("cache_v", CACHE_VERSION).apply()
                publish()
            }
        } catch (e: Exception) {
            error.value = "تعذّر الوصول لدليل المحطات"
        } finally {
            loading.value = false
        }
    }

    private suspend fun buildIntl(d: Intl): Station? {
        val rows = mutableListOf<JSONObject>()
        for (q in d.queries) {
            val arr = runCatching {
                fetchJson("stations/search?name=${URLEncoder.encode(q, "UTF-8")}&hidebroken=true&order=votes&reverse=true&limit=60")
            }.getOrNull() ?: continue
            for (i in 0 until arr.length()) rows += arr.getJSONObject(i)
        }
        data class V(val v: Variant, val ok: Boolean, val preferred: Boolean)
        val found = mutableListOf<V>()
        for (o in rows) {
            val n = o.optString("name")
            if (!d.match.containsMatchIn(n) || d.exclude?.containsMatchIn(n) == true) continue
            if (d.language != null && !o.optString("language").contains(d.language, true)) continue
            val u = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (u.isBlank() || found.any { it.v.url == u }) continue
            found += V(Variant(u, o.optInt("bitrate"), o.optString("codec"), o.optInt("hls") == 1 || u.contains(".m3u8", true)),
                o.optInt("lastcheckok", 1) == 1, d.prefer?.containsMatchIn(n) == true)
        }
        if (found.isEmpty()) return null
        val sorted = found.sortedWith(compareBy({ !it.ok }, { !it.preferred }, { if (it.v.bitrate > 0) it.v.bitrate else 999 }))
        return Station(d.key, d.en, d.ar, 0, sorted.take(6).map { it.v }, intl = true)
    }

    private suspend fun fetchJson(path: String): JSONArray = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (h in HOSTS.shuffled()) {
            try {
                val c = URL("https://$h/json/$path").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "OmanRadio/1.0")
                try {
                    if (c.responseCode == 200) return@withContext JSONArray(c.inputStream.bufferedReader().readText())
                } finally { c.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("directory")
    }

    private fun group(raw: List<JSONObject>): List<Station> {
        data class G(var name: String, var ar: String, var votes: Int, val v: MutableList<Pair<Variant, Boolean>>)
        val map = LinkedHashMap<String, G>()
        for (s in raw) {
            val name = s.optString("name").trim()
            val resolved = s.optString("url_resolved").ifBlank { s.optString("url") }
            if (name.isBlank() || resolved.isBlank()) continue
            val key = Station.keyOf(name)
            if (key.isBlank()) continue
            val g = map.getOrPut(key) { G(name, Station.arabicPart(name), 0, mutableListOf()) }
            if (g.ar.isBlank() && Station.arabicPart(name).isNotBlank()) { g.ar = Station.arabicPart(name); g.name = name }
            g.votes = maxOf(g.votes, s.optInt("votes"))
            val ok = s.optInt("lastcheckok", 1) == 1
            fun add(u: String) {
                if (u.isBlank() || g.v.any { it.first.url == u }) return
                g.v += Variant(u, s.optInt("bitrate"), s.optString("codec"),
                    s.optInt("hls") == 1 || u.contains(".m3u8", true)) to ok
            }
            add(resolved); add(s.optString("url"))
        }
        return map.map { (k, g) ->
            // Working streams first, then the lowest bitrate (unknown bitrate goes last).
            val sorted = g.v.sortedWith(compareBy({ !it.second }, { if (it.first.bitrate > 0) it.first.bitrate else 999 }))
            Station(k, g.name, g.ar, g.votes, sorted.map { it.first })
        }.filter { it.variants.isNotEmpty() }.sortedByDescending { it.votes }
    }

    private fun toJson(list: List<Station>) = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().put("k", s.key).put("n", s.name).put("a", s.ar).put("v", s.votes).put("i", s.intl)
                .put("u", JSONArray().apply {
                    s.variants.forEach { put(JSONObject().put("u", it.url).put("b", it.bitrate).put("c", it.codec).put("h", it.hls)) }
                }))
        }
    }

    private fun parseCache(json: String, custom: Boolean = false): List<Station> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val u = o.getJSONArray("u")
            Station(o.getString("k"), o.getString("n"), o.optString("a"), o.optInt("v"),
                (0 until u.length()).map { j ->
                    val x = u.getJSONObject(j)
                    Variant(x.getString("u"), x.optInt("b"), x.optString("c"), x.optBoolean("h"))
                }, custom, o.optBoolean("i"))
        }
    }.getOrDefault(emptyList())
}
