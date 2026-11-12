package om.radio.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * HF listening through the public KiwiSDR receiver network.
 *
 * A station URI looks like  kiwi://auto?f=9650&m=am&lat=23.6&lon=58.5
 * The data source picks a nearby receiver with a free slot, opens its audio WebSocket,
 * decodes the compressed (IMA-ADPCM) audio and hands ExoPlayer a never-ending WAV stream,
 * so HF stations play, switch and show on the lock screen exactly like FM ones.
 */

/** IMA-ADPCM decoder as used by KiwiSDR (lower nibble first, state kept across packets). */
class ImaAdpcm {
    private var index = 0
    private var prev = 0
    fun preset(i: Int, p: Int) { index = i.coerceIn(0, STEP.lastIndex); prev = p }

    private fun sample(code: Int): Int {
        val step = STEP[index]
        index = (index + ADJ[code]).coerceIn(0, STEP.lastIndex)
        var diff = step shr 3
        if (code and 1 != 0) diff += step shr 2
        if (code and 2 != 0) diff += step shr 1
        if (code and 4 != 0) diff += step
        if (code and 8 != 0) diff = -diff
        prev = (prev + diff).coerceIn(-32768, 32767)
        return prev
    }

    /** Returns 16-bit little-endian PCM. */
    fun decode(data: ByteArray, from: Int): ByteArray {
        val out = ByteArray((data.size - from) * 4)
        var o = 0
        for (i in from until data.size) {
            val b = data[i].toInt() and 0xFF
            for (code in intArrayOf(b and 0x0F, b shr 4)) {
                val s = sample(code)
                out[o++] = (s and 0xFF).toByte(); out[o++] = (s shr 8).toByte()
            }
        }
        return out
    }

    companion object {
        private val STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767,
        )
        private val ADJ = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    }
}

/** Public KiwiSDR receivers, nearest first. */
object KiwiDirectory {
    data class Rx(val url: String, val name: String, val lat: Double, val lon: Double,
                  val users: Int, val max: Int, val lowHz: Long, val highHz: Long)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private val SOURCES = listOf("http://kiwisdr.com/public/", "http://rx.linkfanel.net/kiwisdr_com.js")
    @Volatile private var cache: List<Rx> = emptyList()
    @Volatile private var cacheAt = 0L
    private val failedAt = HashMap<String, Long>()

    fun markFailed(url: String) = synchronized(failedAt) { failedAt[url] = System.currentTimeMillis() }

    private fun recentlyFailed(url: String) = synchronized(failedAt) {
        (failedAt[url] ?: 0L) > System.currentTimeMillis() - 10 * 60_000L
    }

    /** Blocking; call off the main thread. */
    fun candidates(lat: Double, lon: Double, hz: Long): List<Rx> {
        RadioRepo.manualReceiver?.let { m ->
            val url = if (m.startsWith("http")) m else "http://$m"
            return listOf(Rx(url.trimEnd('/'), m, lat, lon, 0, 99, 0, Long.MAX_VALUE))
        }
        if (cache.isEmpty() || System.currentTimeMillis() - cacheAt > 30 * 60_000L) load()
        return cache.filter { it.users < it.max && hz in it.lowHz..it.highHz && !recentlyFailed(it.url) }
            .sortedBy { distanceKm(lat, lon, it.lat, it.lon) }
    }

    private fun load() {
        for (src in SOURCES) {
            val text = runCatching {
                http.newCall(Request.Builder().url(src).header("User-Agent", "OmanRadio/1.0").build()).execute()
                    .use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull() ?: continue
            val list = parse(text)
            if (list.size >= 5) { cache = list; cacheAt = System.currentTimeMillis(); return }
        }
    }

    private val reUrl = Regex("""(?:href|"url")\s*[=:]\s*['"](https?://[^'"\s/]+(?::\d+)?)""")
    private val reGps = Regex("""gps"?\s*[=:]\s*"?\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)""")
    private val reUsers = Regex("""[^_]users"?\s*[=:]\s*"?(\d+)""")
    private val reMax = Regex("""users_max"?\s*[=:]\s*"?(\d+)""")
    private val reOffline = Regex("""offline"?\s*[=:]\s*"?(yes|no)""")
    private val reBands = Regex("""bands"?\s*[=:]\s*"?(\d+)-(\d+)""")
    private val reName = Regex("""name"?\s*[=:]\s*"?([^"<>\n]{2,80}?)\s*(?:-->|",|"\s*$)""", RegexOption.MULTILINE)

    /** Tolerant parser for both the kiwisdr.com/public HTML page and the kiwisdr_com.js map list. */
    private fun parse(text: String): List<Rx> {
        val blocks = when {
            text.contains("cl-entry") -> text.split("cl-entry")
            else -> text.split(Regex("\\n\\s*\\{"))
        }
        return blocks.drop(1).mapNotNull { b ->
            val url = reUrl.find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val gps = reGps.find(b) ?: return@mapNotNull null
            if (reOffline.find(b)?.groupValues?.get(1) == "yes") return@mapNotNull null
            val bands = reBands.find(b)
            Rx(
                url = url,
                name = reName.find(b)?.groupValues?.get(1)?.trim() ?: url,
                lat = gps.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null,
                lon = gps.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null,
                users = reUsers.find(b)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                max = reMax.find(b)?.groupValues?.get(1)?.toIntOrNull() ?: 4,
                lowHz = bands?.groupValues?.get(1)?.toLongOrNull() ?: 0,
                highHz = bands?.groupValues?.get(2)?.toLongOrNull() ?: 30_000_000,
            )
        }.distinctBy { it.url }
    }

    fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = Math.PI / 180
        val a = sin((la2 - la1) * r / 2).let { it * it } +
            cos(la1 * r) * cos(la2 * r) * sin((lo2 - lo1) * r / 2).let { it * it }
        return 12742 * asin(sqrt(a))
    }
}

@OptIn(UnstableApi::class)
class KiwiDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var ws: WebSocket? = null
    private val queue = LinkedBlockingDeque<ByteArray>()
    private var cur: ByteArray? = null
    private var curPos = 0
    @Volatile private var failure: IOException? = null
    private var opened = false
    private var keepalive: ScheduledFuture<*>? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        val khz = u.getQueryParameter("f")?.toDoubleOrNull() ?: throw IOException("no frequency")
        val mode = u.getQueryParameter("m") ?: "am"
        val lat = u.getQueryParameter("lat")?.toDoubleOrNull() ?: 23.6
        val lon = u.getQueryParameter("lon")?.toDoubleOrNull() ?: 58.5

        val rxs = KiwiDirectory.candidates(lat, lon, (khz * 1000).toLong())
        if (rxs.isEmpty()) throw IOException("لا يوجد مستقبِل HF متاح الآن")
        var rate = 0
        for (rx in rxs.take(4)) {
            rate = connect(rx, khz, mode)
            if (rate > 0) { RadioRepo.hfReceiver.value = u.toString() to rx.name; break }
            KiwiDirectory.markFailed(rx.url)
            disconnect()
        }
        if (rate <= 0) throw IOException("تعذّر الاتصال بمستقبِلات HF")

        cur = wavHeader(rate)
        curPos = 0
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    /** Returns the audio sample rate once the receiver accepted us, or 0. */
    private fun connect(rx: KiwiDirectory.Rx, khz: Double, mode: String): Int {
        failure = null
        queue.clear()
        val ready = CountDownLatch(1)
        val sampleRate = AtomicInteger(0)
        val decoder = ImaAdpcm()
        val pass = PASSBANDS[mode] ?: PASSBANDS.getValue("am")

        val ts = runCatching {
            KiwiDirectory.http.newCall(Request.Builder().url("${rx.url}/VER").build()).execute()
                .use { JSONObject(it.body!!.string()).getLong("ts") }
        }.getOrElse { System.currentTimeMillis() / 1000 }

        val wsUrl = rx.url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/kiwi/$ts/SND"
        val listener = object : WebSocketListener() {
            var started = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("SET auth t=kiwi p=#")
                webSocket.send("SET ident_user=OmanRadio")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onBytes(webSocket, text.toByteArray())
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onBytes(webSocket, bytes.toByteArray())

            fun onBytes(w: WebSocket, b: ByteArray) {
                if (b.size < 3) return
                val tag = String(b, 0, 3, Charsets.ISO_8859_1)
                if (tag == "MSG") {
                    val p = parseMsg(String(b, 3, b.size - 3, Charsets.UTF_8))
                    if (p["badp"]?.let { it != "0" } == true || p.containsKey("too_busy") || p.containsKey("down")) {
                        failure = IOException("المستقبِل مشغول"); ready.countDown(); w.close(1000, null); return
                    }
                    p["audio_adpcm_state"]?.split(",")?.let { s ->
                        if (s.size == 2) decoder.preset(s[0].toIntOrNull() ?: 0, s[1].toIntOrNull() ?: 0)
                    }
                    p["audio_rate"]?.let { w.send("SET AR OK in=$it out=44100") }
                    val sr = p["sample_rate"]?.toDoubleOrNull()
                    if (!started && sr != null) {
                        started = true
                        sampleRate.set(sr.roundToInt())
                        w.send("SET mod=$mode low_cut=${pass.first} high_cut=${pass.second} freq=${"%.3f".format(java.util.Locale.US, khz)}")
                        w.send("SET agc=1 hang=0 thresh=-100 slope=6 decay=500 manGain=49")
                        w.send("SET compression=1")
                        w.send("SET squelch=0 max=0")
                        w.send("SET keepalive")
                        keepalive = SCHED.scheduleAtFixedRate({ runCatching { w.send("SET keepalive") } }, 2, 2, TimeUnit.SECONDS)
                        ready.countDown()
                    }
                } else if (tag == "SND" && b.size > 10) {
                    val flags = b[3].toInt() and 0xFF
                    val pcm = if (flags and 0x10 != 0) decoder.decode(b, 10)
                    else ByteArray(b.size - 10).also { out ->
                        val le = flags and 0x80 != 0
                        var i = 10; var o = 0
                        while (i + 1 < b.size) {
                            if (le) { out[o] = b[i]; out[o + 1] = b[i + 1] } else { out[o] = b[i + 1]; out[o + 1] = b[i] }
                            i += 2; o += 2
                        }
                    }
                    queue.offerLast(pcm)
                    // Never let more than ~8 s of audio pile up.
                    val limit = sampleRate.get().coerceAtLeast(8000) * 2 * 8
                    var total = 0
                    for (c in queue) total += c.size
                    while (total > limit) { total -= queue.pollFirst()?.size ?: break }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = IOException("انقطع الاتصال بمستقبِل HF", t); queue.offerLast(EOF); ready.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (failure == null) failure = IOException("أغلق المستقبِل الاتصال")
                queue.offerLast(EOF); ready.countDown()
            }
        }
        ws = WS_CLIENT.newWebSocket(Request.Builder().url(wsUrl).header("User-Agent", "OmanRadio/1.0").build(), listener)
        val ok = ready.await(12, TimeUnit.SECONDS)
        return if (ok && failure == null) sampleRate.get() else 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val c = cur
            if (c != null && curPos < c.size) {
                val n = minOf(length, c.size - curPos)
                System.arraycopy(c, curPos, buffer, offset, n)
                curPos += n
                bytesTransferred(n)
                return n
            }
            val next = queue.pollFirst(15, TimeUnit.SECONDS) ?: throw IOException("لا يصل صوت من مستقبِل HF")
            if (next === EOF) throw failure ?: IOException("انتهى البث")
            cur = next
            curPos = 0
        }
    }

    override fun getUri(): Uri? = uri

    private fun disconnect() {
        keepalive?.cancel(false); keepalive = null
        ws?.close(1000, null); ws = null
        queue.clear()
    }

    override fun close() {
        disconnect()
        cur = null
        if (opened) { opened = false; transferEnded() }
        uri = null
    }

    companion object {
        private val EOF = ByteArray(0)
        private val SCHED = Executors.newSingleThreadScheduledExecutor()
        private val WS_CLIENT = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

        val PASSBANDS = mapOf(
            "am" to (-4900 to 4900), "amn" to (-2500 to 2500),
            "lsb" to (-2700 to -300), "usb" to (300 to 2700), "cw" to (300 to 700),
        )

        fun parseMsg(body: String): Map<String, String> = body.trim().split(' ').filter { it.isNotEmpty() }
            .associate { t -> t.indexOf('=').let { if (it < 0) t to "" else t.substring(0, it) to t.substring(it + 1) } }

        /** Mono 16-bit WAV header with an open-ended data size (live stream). */
        fun wavHeader(rate: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(0x7FFFFFF0); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
            b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(0x7FFFFFC8)
            return b.array()
        }
    }
}

/** Sends kiwi:// URIs to the KiwiSDR source and everything else to the normal HTTP stack. */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val normal: DataSource) : DataSource {
    private val kiwi = KiwiDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        kiwi.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val src = if (dataSpec.uri.scheme == "kiwi") kiwi else normal
        active = src
        return src.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { active?.close(); active = null }

    class Factory(private val normal: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(normal.createDataSource())
    }
}
