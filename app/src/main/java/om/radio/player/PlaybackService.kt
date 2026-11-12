package om.radio.player

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Live radio has no "track position", so previous/next always change station
 * (the default would restart the current stream). Play from a stopped state reconnects.
 */
class RadioPlayer(player: Player) : ForwardingPlayer(player) {
    override fun seekToNext() = seekToNextMediaItem()
    override fun seekToPrevious() = seekToPreviousMediaItem()
    override fun play() {
        if (playbackState == Player.STATE_IDLE) prepare()
        super.play()
    }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && playbackState == Player.STATE_IDLE) prepare()
        super.setPlayWhenReady(playWhenReady)
    }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var list: List<Station> = emptyList()
    private var keys: List<String> = emptyList()
    private val variantIndex = HashMap<String, Int>()
    private val artCache = HashMap<String, ByteArray>()
    private var failHops = 0
    private var hfRetries = 0

    // A paused live stream keeps downloading; after a minute of pause we close the connection.
    private val dropConnection = Runnable { if (!exo.playWhenReady) exo.stop() }

    private val usageTick = object : Runnable {
        override fun run() {
            if (exo.isPlaying) {
                val kbps = current()?.kbps?.takeIf { it > 0 } ?: 64
                RadioRepo.addUsage(kbps * 10 / 8.0 / 1024.0)
            }
            handler.postDelayed(this, 10_000)
        }
    }

    private val sleep = Runnable {
        exo.pause()
        exo.stop()
        RadioRepo.sleepAt.value = 0L
    }

    override fun onCreate() {
        super.onCreate()
        RadioRepo.init(this)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("OmanRadio/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        // Small buffer: enough to ride over short signal drops without pre-loading minutes of audio.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 12_000, 1_500, 3_000)
            .build()

        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(DefaultDataSource.Factory(this, http))))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL
        exo.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, RadioPlayer(exo)).setSessionActivity(open).build()

        scope.launch { RadioRepo.stations.collect { applyPlaylist(it) } }
        scope.launch {
            RadioRepo.sleepAt.collect { at ->
                handler.removeCallbacks(sleep)
                if (at > 0) handler.postDelayed(sleep, maxOf(0L, at - System.currentTimeMillis()))
            }
        }
        scope.launch { RadioRepo.refresh() }
        handler.post(usageTick)
    }

    private fun current(): Station? {
        val id = exo.currentMediaItem?.mediaId ?: return null
        return list.firstOrNull { it.key == id }
    }

    private fun applyPlaylist(stations: List<Station>) {
        val newKeys = stations.map { it.key }
        list = stations
        if (newKeys == keys) {
            // Same stations; an HF frequency may have been fine-tuned → swap just that item.
            for (i in 0 until minOf(exo.mediaItemCount, stations.size)) {
                val st = stations[i]
                if (st.hf == null) continue
                val want = st.variants.first().url
                if (exo.getMediaItemAt(i).localConfiguration?.uri?.toString() != want) {
                    exo.replaceMediaItem(i, st.toItem(0))
                    if (i == exo.currentMediaItemIndex && exo.playWhenReady) exo.prepare()
                }
            }
            return
        }
        val keep = exo.currentMediaItem?.mediaId ?: RadioRepo.currentKey
        keys = newKeys
        if (stations.isEmpty()) { exo.clearMediaItems(); return }
        val start = newKeys.indexOf(keep).coerceAtLeast(0)
        exo.setMediaItems(stations.map { it.toItem(variantIndex[it.key] ?: 0) }, start, C.TIME_UNSET)
    }

    private fun Station.toItem(vi: Int): MediaItem {
        val v = variants[vi.coerceIn(0, variants.lastIndex)]
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(hf?.let { "موجات قصيرة · ${it.khz.fmtKhz()} kHz ${it.modeLabel}" } ?: "راديو عُمان · بث مباشر")
            .setStation(title)
            .setArtworkData(artwork(this), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val b = MediaItem.Builder().setMediaId(key).setUri(v.url).setMediaMetadata(meta)
        if (v.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (hf != null) b.setMimeType(MimeTypes.AUDIO_WAV)
        return b.build()
    }

    /** Lock-screen artwork drawn on the phone, so no logo downloads. */
    private fun artwork(s: Station): ByteArray = artCache.getOrPut(s.key) {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF16262E.toInt())
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFFE3A540.toInt()
        }
        c.drawCircle(size / 2f, size / 2f, size * 0.36f, ring)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFECE4D6.toInt(); textSize = size * 0.36f
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(s.monogram, size / 2f, y, text)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            handler.removeCallbacks(dropConnection)
            if (!playWhenReady) handler.postDelayed(dropConnection, 60_000)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) { failHops = 0; hfRetries = 0; RadioRepo.error.value = null }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            hfRetries = 0
            mediaItem?.mediaId?.let { RadioRepo.currentKey = it }
        }

        override fun onPlayerError(error: PlaybackException) {
            val idx = exo.currentMediaItemIndex
            val st = current() ?: return
            // HF: a receiver dropped us or was full → try again (the next nearest receiver is picked).
            if (st.hf != null) {
                if (hfRetries < 2) {
                    hfRetries++
                    RadioRepo.error.value = "جاري تجربة مستقبِل آخر…"
                    exo.prepare(); exo.play()
                } else {
                    hfRetries = 0
                    RadioRepo.error.value = error.cause?.message ?: "تعذّر الاتصال بمستقبِلات HF"
                }
                return
            }
            // 1) try the station's other stream addresses
            val next = (variantIndex[st.key] ?: 0) + 1
            if (next < st.variants.size) {
                variantIndex[st.key] = next
                exo.replaceMediaItem(idx, st.toItem(next))
                exo.prepare(); exo.play()
                return
            }
            variantIndex.remove(st.key)
            exo.replaceMediaItem(idx, st.toItem(0))
            // 2) station is down: move on (useful when the phone is locked)
            failHops++
            if (failHops < minOf(5, list.size)) {
                RadioRepo.error.value = "تعذّر تشغيل «${st.title}»، الانتقال للتالية"
                exo.seekToNextMediaItem()
                exo.prepare(); exo.play()
            } else {
                failHops = 0
                RadioRepo.error.value = "تعذّر الاتصال. تحقق من الإنترنت"
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
