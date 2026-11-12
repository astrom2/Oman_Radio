package om.radio.player

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------------- colours ---------------- */

data class RadioColors(
    val bg: Color, val surface: Color, val raise: Color, val ink: Color, val muted: Color,
    val accent: Color, val accentInk: Color, val line: Color, val live: Color, val ok: Color,
)

private val Dark = RadioColors(
    bg = Color(0xFF0F1B21), surface = Color(0xFF16262E), raise = Color(0xFF1D313A),
    ink = Color(0xFFECE4D6), muted = Color(0xFF8FA3AD), accent = Color(0xFFE3A540),
    accentInk = Color(0xFF1A1206), line = Color(0xFF28404A), live = Color(0xFFEF6A5E), ok = Color(0xFF5CC391),
)
private val Light = RadioColors(
    bg = Color(0xFFEEF1F0), surface = Color(0xFFFFFFFF), raise = Color(0xFFE3E9E8),
    ink = Color(0xFF14232A), muted = Color(0xFF5B6F78), accent = Color(0xFFB06F14),
    accentInk = Color(0xFFFFFFFF), line = Color(0xFFCDD6D6), live = Color(0xFFC8433D), ok = Color(0xFF2F7D5B),
)
val LocalColors = staticCompositionLocalOf { Dark }

/* ---------------- player state mirrored for the UI ---------------- */

class PlayerUi : Player.Listener {
    var mediaId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var wantPlay by mutableStateOf(false)
    var buffering by mutableStateOf(false)

    fun sync(p: Player) {
        mediaId = p.currentMediaItem?.mediaId
        playing = p.isPlaying
        wantPlay = p.playWhenReady
        buffering = p.playbackState == Player.STATE_BUFFERING
    }

    override fun onEvents(player: Player, events: Player.Events) = sync(player)
}

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)
    private val ui = PlayerUi()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RadioRepo.init(this)
        setContent {
            val c = if (isSystemInDarkTheme()) Dark else Light
            val scheme = if (c == Dark) darkColorScheme(primary = c.accent, surface = c.surface, background = c.bg, onSurface = c.ink)
            else lightColorScheme(primary = c.accent, surface = c.surface, background = c.bg, onSurface = c.ink)
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalColors provides c, LocalLayoutDirection provides LayoutDirection.Rtl) {
                    RadioScreen(controller.value, ui)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            runCatching { f.get() }.getOrNull()?.let { c ->
                c.addListener(ui)
                ui.sync(c)
                controller.value = c
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller.value?.removeListener(ui)
        controller.value = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

/* ---------------- screen ---------------- */

private val SLEEP_OPTIONS = listOf(0, 15, 30, 60, 90)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioScreen(player: MediaController?, ui: PlayerUi) {
    val col = LocalColors.current
    val stations by RadioRepo.stations.collectAsState()
    val saver by RadioRepo.saver.collectAsState()
    val usage by RadioRepo.usageMb.collectAsState()
    val loading by RadioRepo.loading.collectAsState()
    val error by RadioRepo.error.collectAsState()
    val sleepAt by RadioRepo.sleepAt.collectAsState()
    val hiddenCount by RadioRepo.hiddenCount.collectAsState()
    val receiver by RadioRepo.hfReceiver.collectAsState()
    val manualRx by RadioRepo.manualRx.collectAsState()
    var editingRx by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val cur = stations.indexOfFirst { it.key == ui.mediaId }.let { i ->
        if (i >= 0) i else stations.indexOfFirst { it.key == RadioRepo.currentKey }.coerceAtLeast(0)
    }

    val pager = rememberPagerState(initialPage = cur) { stations.size }
    var userSwiped by remember { mutableStateOf(false) }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val latestCur by rememberUpdatedState(cur)
    val latestPlayer by rememberUpdatedState(player)

    LaunchedEffect(dragged) { if (dragged) userSwiped = true }
    // Swipe on the dial → change station
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            if (userSwiped && p != latestCur) {
                latestPlayer?.seekToDefaultPosition(p)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            userSwiped = false
        }
    }
    // Station changed elsewhere (lock screen, list, auto-skip) → move the dial
    LaunchedEffect(cur, stations.size) {
        if (stations.isNotEmpty() && cur < stations.size && pager.currentPage != cur && !pager.isScrollInProgress) {
            pager.animateScrollToPage(cur)
        }
    }

    var sleepSel by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepAt) { if (sleepAt == 0L) sleepSel = 0 }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Station?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(col.bg).windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("راديو عُمان", color = col.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "استهلاك الشهر: ${if (usage < 10) "%.1f".format(usage) else usage.roundToInt().toString()} م.ب",
                    color = col.muted, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(col.raise).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(col.surface)
                    .border(1.dp, col.line, RoundedCornerShape(22.dp)).padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusLine(ui, error)
                Spacer(Modifier.height(6.dp))
                if (stations.isEmpty()) {
                    Box(Modifier.height(110.dp), contentAlignment = Alignment.Center) {
                        Text(if (loading) "جاري جلب محطات عُمان…" else "لا توجد محطات بعد", color = col.muted)
                    }
                } else {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(124.dp)) { page ->
                        val s = stations[page]
                        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            if (s.intl) Text("إذاعة دولية", color = col.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            s.hf?.let { h ->
                                Text("موجات قصيرة · ${h.khz.fmtKhz()} kHz ${h.modeLabel}", color = col.accent, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold, style = TextStyle(textDirection = TextDirection.Rtl))
                            }
                            Text(s.title, color = col.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
                            val sub = if (s.hf != null) listOfNotNull(
                                (if (s.hf.onAirNow()) "على الهواء الآن" else "يبث ${s.hf.scheduleText()}"),
                                "≈ ${mbPerHour(s.kbps).roundToInt()} م.ب/ساعة",
                            ).joinToString(" · ") else listOfNotNull(
                                s.subtitle.takeIf { it.isNotBlank() },
                                s.kbps.takeIf { it > 0 }?.let { "$it kbps" },
                                s.kbps.takeIf { it > 0 }?.let { "≈ ${mbPerHour(it).roundToInt()} م.ب/ساعة" },
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, color = col.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                                style = TextStyle(textDirection = TextDirection.Content))
                        }
                    }
                }
                TuningScale(pager, stations.size)
                val curStation = stations.getOrNull(cur)
                if (curStation?.hf != null) HfTuner(curStation, receiver)
                Text("اسحب يميناً للمحطة التالية، ويساراً للسابقة", color = col.muted, fontSize = 12.sp)
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                RoundKey("المحطة السابقة", 52, onClick = {
                    player?.seekToPreviousMediaItem(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }) { SkipGlyph(towardRight = true) }
                PlayKey(ui) {
                    val p = player ?: return@PlayKey
                    if (p.playWhenReady) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
                }
                RoundKey("المحطة التالية", 52, onClick = {
                    player?.seekToNextMediaItem(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }) { SkipGlyph(towardRight = false) }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Pill("توفير البيانات", selected = saver) { RadioRepo.setSaver(!saver) }
                Pill(if (sleepSel == 0) "مؤقت الإيقاف: بدون" else "إيقاف بعد ${SLEEP_OPTIONS[sleepSel]} د", selected = sleepSel != 0) {
                    sleepSel = (sleepSel + 1) % SLEEP_OPTIONS.size
                    val m = SLEEP_OPTIONS[sleepSel]
                    RadioRepo.sleepAt.value = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("المحطات (${stations.size})", color = col.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("إضافة رابط", color = col.accent) }
                TextButton(onClick = { scope.launch { RadioRepo.refresh(force = true) } }, enabled = !loading) {
                    Text(if (loading) "جاري التحديث…" else "تحديث", color = col.accent)
                }
            }
        }

        if (stations.isEmpty() && !loading) {
            item {
                Text(
                    if (saver) "لا توجد محطات بمعدل ${RadioRepo.SAVER_MAX_KBPS} kbps أو أقل. أوقف «توفير البيانات» لعرض الكل."
                    else "اضغط «تحديث» للمحاولة مجدداً، أو أضف محطة برابط مباشر.",
                    color = col.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }

        stations.forEachIndexed { i, s ->
            val section = sectionOf(s)
            if (i == 0 || sectionOf(stations[i - 1]) != section) {
                item(key = "h-$section") {
                    if (s.hf != null && !s.hf.world) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionHeader(section) }
                            TextButton(onClick = { editingRx = true }) {
                                Text(if (manualRx == null) "المستقبِل: تلقائي" else "المستقبِل: $manualRx", color = col.accent, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else SectionHeader(section)
                }
            }
            item(key = s.key) {
                StationRow(s, i == cur,
                    onClick = { player?.let { p -> p.seekToDefaultPosition(i); if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() } },
                    onLongClick = { removing = s })
            }
        }
        if (hiddenCount > 0) {
            item(key = "unhide") {
                TextButton(onClick = { RadioRepo.unhideAll() }, modifier = Modifier.fillMaxWidth()) {
                    Text("إظهار المحطات المخفية ($hiddenCount)", color = col.accent)
                }
            }
        }
    }

    if (editingRx) ReceiverDialog(manualRx, onDismiss = { editingRx = false }) { RadioRepo.manualReceiver = it; editingRx = false }
    if (adding) AddStationDialog(onDismiss = { adding = false }) { n, u, k -> RadioRepo.addCustom(n, u, k); adding = false }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(if (s.custom) "حذف «${s.title}»؟" else "إخفاء «${s.title}» من القائمة؟") },
            confirmButton = {
                TextButton(onClick = {
                    if (s.custom) RadioRepo.removeCustom(s.key) else RadioRepo.hide(s.key)
                    removing = null
                }) { Text(if (s.custom) "حذف" else "إخفاء") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("إلغاء") } },
        )
    }
}

private fun sectionOf(s: Station) = when {
    s.hf != null && s.hf.world -> "موجات قصيرة HF · عالمية"
    s.hf != null -> "موجات قصيرة HF · من منطقتنا"
    s.custom -> "محطاتك"
    s.intl -> "إذاعات دولية"
    else -> "إذاعات عُمان"
}

@Composable
private fun SectionHeader(title: String) {
    val col = LocalColors.current
    Text(title, color = col.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

/** −5 / −1 / +1 / +5 kHz nudges for HF, plus the receiver in use. */
@Composable
private fun HfTuner(s: Station, receiver: Pair<String, String>?) {
    val col = LocalColors.current
    val h = s.hf ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 8.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (d in listOf(-5.0, -1.0, 1.0, 5.0)) {
                    Text(
                        (if (d > 0) "+" else "−") + abs(d).toInt(), color = col.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, col.line, RoundedCornerShape(10.dp))
                            .clickable { RadioRepo.tuneHf(s.key, d) }.padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { contentDescription = "تحريك التردد ${d.toInt()} كيلوهرتز" },
                    )
                    if (d == -1.0) Text("kHz", color = col.muted, fontSize = 12.sp)
                }
            }
        }
        val preset = RadioRepo.presetKhz(s.key)
        if (preset != null && preset != h.khz) {
            Text("رجوع إلى ${preset.fmtKhz()} kHz", color = col.accent, fontSize = 12.sp,
                modifier = Modifier.clickable { RadioRepo.resetTune(s.key) }.padding(4.dp))
        }
        val rxName = receiver?.takeIf { it.first == s.variants.firstOrNull()?.url }?.second
        if (rxName != null) Text("عبر مستقبِل: $rxName", color = col.muted, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun ReceiverDialog(current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مستقبِل HF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("يختار التطبيق تلقائياً أقرب مستقبِل KiwiSDR متاح. لتثبيت مستقبِل معيّن اكتب عنوانه، مثل example.proxy.kiwisdr.com:8073")
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(text, { text = it.trim() }, singleLine = true, label = { Text("host:port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.ifBlank { null }) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = { onSave(null) }) { Text("تلقائي") } },
    )
}

@Composable
private fun StatusLine(ui: PlayerUi, error: String?) {
    val col = LocalColors.current
    val (text, color) = when {
        ui.playing -> "مباشر" to col.live
        ui.wantPlay && error != null -> error to col.muted
        ui.wantPlay -> "جاري الاتصال…" to col.muted
        error != null -> error to col.muted
        else -> "متوقف" to col.muted
    }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(ui.playing) { while (ui.playing) { delay(800); blink = !blink }; blink = true }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ui.playing && !blink) color.copy(alpha = .35f) else color))
        Text(text, color = color, fontSize = 12.sp)
    }
}

/** Radio tuning scale: ticks slide under a fixed needle as the dial is swiped. */
@Composable
private fun TuningScale(pager: PagerState, count: Int) {
    val col = LocalColors.current
    Canvas(Modifier.fillMaxWidth().height(46.dp).padding(vertical = 4.dp)) {
        val pos = pager.currentPage + pager.currentPageOffsetFraction
        val markW = 64.dp.toPx()
        val tick = 10.dp.toPx()
        val cx = size.width / 2f
        val base = size.height * 0.62f
        fun fade(x: Float) = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)

        var x = ((pos * markW) % tick + tick) % tick
        while (x < size.width) {
            drawLine(col.line.copy(alpha = fade(x)), Offset(x, base - 8.dp.toPx()), Offset(x, base), 1.dp.toPx())
            x += tick
        }
        // Stations sit to the left of each other (right-to-left order).
        for (i in 0 until count) {
            val sx = cx - (i - pos) * markW
            if (sx < -markW || sx > size.width + markW) continue
            val near = 1f - (abs(sx - cx) / markW).coerceIn(0f, 1f)
            drawLine((if (near > .5f) col.ink else col.muted).copy(alpha = fade(sx)),
                Offset(sx, base - 20.dp.toPx()), Offset(sx, base), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        drawLine(col.accent, Offset(cx, 4.dp.toPx()), Offset(cx, base + 4.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(col.accent, 5.dp.toPx(), Offset(cx, 4.dp.toPx()))
    }
}

@Composable
private fun RoundKey(label: String, sizeDp: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    val col = LocalColors.current
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(col.surface).border(1.dp, col.line, CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SkipGlyph(towardRight: Boolean) {
    val col = LocalColors.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val p = Path()
        if (towardRight) { // ▶|
            p.moveTo(w * .18f, h * .2f); p.lineTo(w * .66f, h * .5f); p.lineTo(w * .18f, h * .8f); p.close()
            drawRect(col.ink, Offset(w * .7f, h * .2f), androidx.compose.ui.geometry.Size(w * .1f, h * .6f))
        } else { // |◀
            p.moveTo(w * .82f, h * .2f); p.lineTo(w * .34f, h * .5f); p.lineTo(w * .82f, h * .8f); p.close()
            drawRect(col.ink, Offset(w * .2f, h * .2f), androidx.compose.ui.geometry.Size(w * .1f, h * .6f))
        }
        drawPath(p, col.ink)
    }
}

@Composable
private fun PlayKey(ui: PlayerUi, onClick: () -> Unit) {
    val col = LocalColors.current
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(col.accent).clickable(onClick = onClick)
            .semantics { contentDescription = if (ui.wantPlay) "إيقاف" else "تشغيل" },
        contentAlignment = Alignment.Center,
    ) {
        if (ui.wantPlay && !ui.playing) {
            CircularProgressIndicator(color = col.accentInk, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        } else {
            Canvas(Modifier.size(30.dp)) {
                val w = size.width; val h = size.height
                if (ui.wantPlay) {
                    drawRect(col.accentInk, Offset(w * .2f, h * .15f), androidx.compose.ui.geometry.Size(w * .2f, h * .7f))
                    drawRect(col.accentInk, Offset(w * .6f, h * .15f), androidx.compose.ui.geometry.Size(w * .2f, h * .7f))
                } else {
                    val p = Path().apply { moveTo(w * .28f, h * .15f); lineTo(w * .85f, h * .5f); lineTo(w * .28f, h * .85f); close() }
                    drawPath(p, col.accentInk)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val col = LocalColors.current
    Text(
        text, fontSize = 13.sp, color = if (selected) col.bg else col.ink,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) col.ink else col.surface)
            .border(1.dp, if (selected) col.ink else col.line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(s: Station, current: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val col = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (current) col.raise else col.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (current) col.accent else col.raise),
            contentAlignment = Alignment.Center) {
            Text(s.monogram, color = if (current) col.accentInk else col.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(s.title, color = col.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = s.hf?.let { h -> "${h.khz.fmtKhz()} kHz ${h.modeLabel} · ${h.scheduleText()}" }
                ?: s.subtitle.ifBlank { if (s.custom) "محطة مضافة · اضغط مطولاً للحذف" else s.variants.firstOrNull()?.codec ?: "" }
            if (sub.isNotBlank()) Text(sub, color = col.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.hf != null) {
            val on = s.hf.onAirNow()
            Text(if (on) "على الهواء" else "خارج الوقت", color = if (on) col.ok else col.muted, fontSize = 12.sp)
        } else Text(
            if (s.kbps > 0) "${s.kbps} kbps · ${mbPerHour(s.kbps).roundToInt()} م.ب/س" else "—",
            color = if (s.kbps in 1..RadioRepo.SAVER_MAX_KBPS) col.ok else col.muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun AddStationDialog(onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kbps by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة محطة برابط مباشر") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم المحطة") }, singleLine = true)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("https://…") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                OutlinedTextField(kbps, { kbps = it.filter(Char::isDigit) }, label = { Text("معدل البت kbps (اختياري)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim(), url, kbps.toIntOrNull() ?: 0) }, enabled = valid) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
