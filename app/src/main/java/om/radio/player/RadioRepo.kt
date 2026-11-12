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

/** A broadcast window in UTC: days are Calendar.SUNDAY..SATURDAY, minutes from 00:00 UTC. */
data class Win(val days: Set<Int>, val startUtc: Int, val endUtc: Int)

data class HfInfo(
    val khz: Double, val mode: String, val world: Boolean,
    val lat: Double, val lon: Double, val windows: List<Win> = emptyList(),
) {
    val alwaysOn get() = windows.isEmpty()

    fun onAirNow(): Boolean {
        if (alwaysOn) return true
        val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val day = c.get(Calendar.DAY_OF_WEEK)
        val min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return windows.any { day in it.days && min >= it.startUtc && min < it.endUtc }
    }

    /** e.g. "19:00–19:30 الأحد–الخميس" in the phone's local time. */
    fun scheduleText(): String {
        if (alwaysOn) return "على مدار الساعة"
        val off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        fun hm(m: Int) = ((m + off) % 1440 + 1440).rem(1440).let { "%02d:%02d".format(java.util.Locale.US, it / 60, it % 60) }
        return windows.joinToString("، ") { w -> "${hm(w.startUtc)}–${hm(w.endUtc)}${dayText(w.days)}" }
    }

    private fun dayText(d: Set<Int>): String = when {
        d.size == 7 -> ""
        d == setOf(1, 2, 3, 4, 5) -> " الأحد–الخميس"
        d == setOf(2, 3, 4, 5, 6) -> " الإثنين–الجمعة"
        d == setOf(1) -> " الأحد"
        else -> ""
    }

    val modeLabel get() = mode.uppercase()
    fun kiwiUri() = "kiwi://auto?f=$khz&m=$mode&lat=$lat&lon=$lon"
}

data class Station(
    val key: String,
    val name: String,
    val ar: String,
    val votes: Int,
    val variants: List<Variant>,
    val custom: Boolean = false,
    val intl: Boolean = false,
    val hf: HfInfo? = null,
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

fun Double.fmtKhz(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(java.util.Locale.US, this)

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
        manualRx.value = manualReceiver
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

    /* ---------------- HF (shortwave) through public KiwiSDR receivers ---------------- */

    // Oman is the default listening spot; worldwide signals use a receiver near the transmitter.
    private const val OM_LAT = 23.6
    private const val OM_LON = 58.5
    private val DAILY = (1..7).toSet()
    private fun w(days: Set<Int>, from: String, to: String): Win {
        fun m(t: String) = t.substring(0, 2).toInt() * 60 + t.substring(2).toInt()
        return Win(days, m(from), m(to))
    }
    private fun hf(key: String, ar: String, en: String, khz: Double, mode: String, vararg wins: Win,
                   world: Boolean = false, lat: Double = OM_LAT, lon: Double = OM_LON) =
        Station(key, en, ar, 0, emptyList(), hf = HfInfo(khz, mode, world, lat, lon, wins.toList()))

    // Broadcast times: British DX Club "Middle East on Shortwave", winter season B26 (to late March 2027).
    private val HF_PRESETS = listOf(
        hf("hf-bbc-ar", "بي بي سي عربي · مرحّل عُمان", "BBC Arabic", 9650.0, "am", w(setOf(1, 2, 3, 4, 5), "1500", "1530")),
        hf("hf-bbc-en", "بي بي سي بالإنجليزية · مرحّل عُمان", "BBC World Service", 12095.0, "am",
            w(setOf(2, 3, 4, 5, 6), "1500", "1700"), w(setOf(1), "1500", "1830")),
        hf("hf-bbc-fa", "بي بي سي فارسي · مرحّل عُمان", "BBC Persian", 6000.0, "am", w(DAILY, "1630", "1700")),
        hf("hf-kuwait", "إذاعة الكويت", "Radio Kuwait", 7250.0, "am", w(DAILY, "0800", "1000")),
        hf("hf-vot-ar", "صوت تركيا بالعربية", "Voice of Turkiye Arabic", 7255.0, "am", w(DAILY, "1500", "1600")),
        hf("hf-vot-en", "صوت تركيا بالإنجليزية", "Voice of Turkiye English", 7285.0, "am", w(DAILY, "0400", "0500")),
        hf("hf-irib-1", "الإذاعة الإيرانية العربية", "IRIB Arabic", 15135.0, "am", w(DAILY, "0600", "0830"), w(DAILY, "1430", "1730")),
        hf("hf-irib-2", "الإذاعة الإيرانية العربية", "IRIB Arabic", 15240.0, "am", w(DAILY, "0930", "1430")),
        hf("hf-ham40", "هواة اللاسلكي · 40 متر", "Amateur 40 m", 7150.0, "lsb"),
        hf("hf-ham20", "هواة اللاسلكي · 20 متر", "Amateur 20 m", 14200.0, "usb"),
        hf("hf-ncdxf", "منارات NCDXF العالمية", "NCDXF beacons", 14100.0, "cw"),
        hf("hf-wwv", "إشارة الوقت WWV · أمريكا", "WWV time signal", 10000.0, "am", world = true, lat = 40.68, lon = -105.04),
        hf("hf-chu", "إشارة الوقت CHU · كندا", "CHU time signal", 7850.0, "usb", world = true, lat = 45.29, lon = -75.75),
        hf("hf-volmet", "شانون فولمت · طقس الطيران", "Shannon VOLMET", 5505.0, "usb", world = true, lat = 52.75, lon = -8.93),
    )
    const val HF_KBPS = 48   // compressed HF audio, about 21 MB per hour

    /** Receiver chosen by the HF player (station link → receiver name), shown on screen. */
    val hfReceiver = MutableStateFlow<Pair<String, String>?>(null)

    /** A fixed receiver (host:port) set by the user, or null for automatic choice. */
    var manualReceiver: String?
        get() = if (::prefs.isInitialized) prefs.getString("rx", null) else null
        set(v) { prefs.edit().putString("rx", v?.trim()?.ifBlank { null }).apply(); manualRx.value = manualReceiver }
    val manualRx = MutableStateFlow<String?>(null)

    private fun hfStations(): List<Station> = HF_PRESETS.map { s ->
        val khz = prefs.getFloat("tune-${s.key}", s.hf!!.khz.toFloat()).toDouble()
        val info = s.hf.copy(khz = khz)
        s.copy(hf = info, variants = listOf(Variant(info.kiwiUri(), HF_KBPS, "HF", false)))
    }

    /** Fine tuning for HF stations, in kHz. */
    fun tuneHf(key: String, deltaKhz: Double) {
        val base = HF_PRESETS.firstOrNull { it.key == key }?.hf ?: return
        val now = prefs.getFloat("tune-$key", base.khz.toFloat()) + deltaKhz.toFloat()
        prefs.edit().putFloat("tune-$key", now.coerceIn(100f, 30_000f)).apply()
        publish()
    }

    fun resetTune(key: String) { prefs.edit().remove("tune-$key").apply(); publish() }

    fun presetKhz(key: String): Double? = HF_PRESETS.firstOrNull { it.key == key }?.hf?.khz

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
        } + hfStations().filter { it.key !in hidden }
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
