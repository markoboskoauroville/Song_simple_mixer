package community.auroville.mantra.mixer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Mantra Mixer: one vocal, one backing track, two faders, a nudge for the vocal's timing, play, and export
 * the mix as WAV. Plain Android views, no libraries: nothing to update but the app itself.
 */
class MainActivity : Activity() {
    private val engine = Engine()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mixer", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: Button
    private lateinit var offsetLabel: TextView
    private lateinit var spaceLabel: TextView
    private lateinit var spaceMeter: TextView
    private val strips = arrayOfNulls<Strip>(2)          // 0 = vocal, 1 = backing
    private var userSeeking = false

    private class Strip(val name: TextView, val fader: SeekBar, val db: TextView, val mute: Button) {
        var muted = false
    }

    private companion object {
        const val PICK_VOCAL = 1
        const val PICK_BACKING = 2
        const val EXPORT = 3
        const val STEPS = (Mix.MAX_DB - Mix.MIN_DB) * 2       // half-dB steps
        const val INK = 0xFFEDEDED.toInt()
        const val SOFT = 0xFFB8B8B8.toInt()
        const val BG = 0xFF6E6E6E.toInt()
        const val CARD = 0xFF5A5A5A.toInt()
        const val BTN = 0xFF1F1F1F.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); addView(root) }
        setContentView(scroll)
        // The bars are not ours (MANTRA_MANIFEST system-bars.md): pad the content inside them.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars()); i.top to i.bottom
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            v.setPadding(0, t, 0, b); insets
        }

        root.addView(text("Mantra Mixer", 24f, INK, bold = true))
        root.addView(text("Vocal and backing track: set the levels, listen, export the mix.", 15f, SOFT).also { it.setPadding(0, dp(2), 0, dp(14)) })

        strips[0] = strip(root, "VOCAL", "Load vocal", PICK_VOCAL, prefs.getFloat("vocalDb", 0f))
        strips[1] = strip(root, "BACKING", "Load backing track", PICK_BACKING, prefs.getFloat("backingDb", 0f))

        // vocal timing nudge
        val nudge = card(root)
        nudge.addView(text("VOCAL TIMING", 13f, SOFT, bold = true))
        offsetLabel = text("", 17f, INK)
        nudge.addView(offsetLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (ms in intArrayOf(-50, -10, -1, 1, 10, 50)) {
            row.addView(button(if (ms > 0) "+$ms" else "$ms", small = true) { setOffsetMs(offsetMs() + ms) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) })
        }
        nudge.addView(row)
        setOffsetMs(prefs.getInt("offsetMs", 0))

        // vocal space: the backing steps aside, band by band, where the vocal sings
        val sp = card(root)
        sp.addView(text("VOCAL SPACE", 13f, SOFT, bold = true))
        spaceLabel = text("", 17f, INK)
        sp.addView(spaceLabel)
        val spaceFader = SeekBar(this).apply {
            max = (Space.MAX_DB * 2).toInt()                     // half-dB steps
            progress = (prefs.getFloat("spaceDb", 3f) * 2).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = setSpace(p / 2.0)
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        sp.addView(spaceFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        spaceMeter = text("", 14f, SOFT)
        sp.addView(spaceMeter)
        setSpace(spaceFader.progress / 2.0)

        // transport
        val tr = card(root)
        time = text("0:00 / 0:00", 17f, INK)
        tr.addView(time)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) engine.position = (engine.length.toLong() * p / 1000).toInt()
                }
                override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(s: SeekBar) { userSeeking = false }
            })
        }
        tr.addView(seek)
        playBtn = button("▶  Play") { togglePlay() }
        tr.addView(playBtn, full())
        root.addView(button("Export mix as WAV") { startExport() }, full().apply { topMargin = dp(12) })

        status = text("Load a vocal and a backing track.", 15f, SOFT).also { it.setPadding(0, dp(14), 0, 0) }
        root.addView(status)
        tick()
        if (savedInstanceState == null) handleShare(intent)   // not again after a rotation
    }

    // ---- building blocks ----------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = if (small) 14f else 17f; setTextColor(INK)
        background = GradientDrawable().apply { setColor(BTN); cornerRadius = dp(12).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) }

    private fun card(root: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }

    private fun strip(root: LinearLayout, title: String, load: String, code: Int, db: Float): Strip {
        val c = card(root)
        c.addView(text(title, 13f, SOFT, bold = true))
        val name = text("—", 16f, INK)
        c.addView(name)
        val dbText = text("", 17f, INK).apply { gravity = Gravity.END }
        val fader = SeekBar(this).apply { max = STEPS; progress = ((db - Mix.MIN_DB) * 2).toInt().coerceIn(0, STEPS) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(fader, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(dbText, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
        c.addView(row)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var s: Strip
        val mute = button("Mute", small = true) { s.muted = !s.muted; applyLevels() }
        btns.addView(button(load, small = true) { pick(code) }, LinearLayout.LayoutParams(0, dp(48), 2f).apply { rightMargin = dp(6) })
        btns.addView(button("0 dB", small = true) { s.fader.progress = -Mix.MIN_DB * 2; applyLevels() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        btns.addView(mute, LinearLayout.LayoutParams(0, dp(48), 1f))
        c.addView(btns)
        s = Strip(name, fader, dbText, mute)
        fader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = applyLevels()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        ui.post { applyLevels() }
        return s
    }

    // ---- levels, timing, transport -----------------------------------------------------------------------

    private fun faderDb(s: Strip) = Mix.MIN_DB + s.fader.progress / 2.0

    private fun applyLevels() {
        val v = strips[0] ?: return
        val b = strips[1] ?: return
        for (s in listOf(v, b)) {
            val db = faderDb(s)
            s.db.text = if (db <= Mix.MIN_DB) "off" else "%+.1f dB".format(db)
            (s.mute.background as GradientDrawable).setColor(if (s.muted) 0xFF8A1C1C.toInt() else BTN)
            s.mute.isSelected = s.muted   // readable by TalkBack, not only by colour
        }
        engine.vocalGain = if (v.muted) 0f else Mix.dbToGain(faderDb(v))
        engine.backingGain = if (b.muted) 0f else Mix.dbToGain(faderDb(b))
        prefs.edit().putFloat("vocalDb", faderDb(v).toFloat()).putFloat("backingDb", faderDb(b).toFloat()).apply()
    }

    private fun offsetMs() = Math.round(engine.vocalOffsetFrames * 1000.0 / Mix.RATE).toInt()

    private fun setOffsetMs(ms: Int) {
        val m = ms.coerceIn(-5000, 5000)
        engine.vocalOffsetFrames = (m.toLong() * Mix.RATE / 1000).toInt()
        offsetLabel.text = when {
            m == 0 -> "In sync (0 ms)"
            m > 0 -> "Vocal $m ms later"
            else -> "Vocal ${-m} ms earlier"
        }
        prefs.edit().putInt("offsetMs", m).apply()
    }

    private fun setSpace(db: Double) {
        engine.spaceDb = db
        spaceLabel.text = if (db <= 0.0) "Off — the backing plays as it is"
            else "Backing up to −%.1f dB where the vocal sings".format(db)
        prefs.edit().putFloat("spaceDb", db.toFloat()).apply()
    }

    private fun togglePlay() {
        if (engine.playing) { engine.pause(); playBtn.text = "▶  Play"; return }
        if (engine.length == 0) { status.text = "Load at least one track first."; return }
        engine.play { ui.post { playBtn.text = "▶  Play" } }
        playBtn.text = "❚❚  Pause"
    }

    private var shownTime = ""
    private var shownSeek = -1
    private var shownMeter = ""

    // Redraw only what changed, and only fast while playing: an idle redraw every 100 ms cost 40-78 % CPU
    // in the emulator (software rendering) and is wasted battery on a phone.
    private fun tick() {
        val len = engine.length
        val t = "${Mix.clock(engine.position)} / ${Mix.clock(len)}"
        if (t != shownTime) { time.text = t; shownTime = t }
        if (!userSeeking && len > 0) {
            val p = (engine.position.toLong() * 1000 / len).toInt()
            if (p != shownSeek) { seek.progress = p; shownSeek = p }
        }
        val m = if (engine.playing && engine.spaceDb > 0) "Now: %.1f dB".format(engine.space.meterDb)
            else "100 Hz – 12 kHz only; kick, sub and air are left alone."
        if (m != shownMeter) { spaceMeter.text = m; shownMeter = m }
        ui.postDelayed({ tick() }, if (engine.playing) 200L else 1000L)
    }

    // ---- files ----------------------------------------------------------------------------------------------

    private fun pick(code: Int) {
        // Not every Android has the document picker (the emulator's test image has none): never crash on it.
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
        val get = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
        for (i in listOf(open, get)) {
            try { startActivityForResult(i, code); return } catch (_: ActivityNotFoundException) {}
        }
        status.text = "No file picker on this phone. Share the audio files to Mantra Mixer instead."
    }

    /** Files shared to the app (Drive, Files, a music app): two at once fill both slots; one goes by its name. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val named = uris.map { it to displayName(it) }
        val looksVocal = { n: String -> Regex("vocal|voc\\b|chops|acapella|a cappella", RegexOption.IGNORE_CASE).containsMatchIn(n) }
        if (named.size >= 2) {
            val v = named.firstOrNull { looksVocal(it.second) } ?: named[0]
            val b = named.first { it !== v }
            load(v.first, true); load(b.first, false)
        } else {
            load(named[0].first, looksVocal(named[0].second))
        }
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleShare(intent) }

    private fun startExport() {
        if (engine.length == 0) { status.text = "Nothing to export yet."; return }
        engine.pause(); playBtn.text = "▶  Play"
        val base = (engine.vocal?.name ?: engine.backing?.name ?: "mix").substringBeforeLast('.')
        val name = "${base}_mix.wav"
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/wav"; putExtra(Intent.EXTRA_TITLE, name)
            }, EXPORT)
        } catch (_: ActivityNotFoundException) {
            // No picker: straight into Downloads (Android 10+, no permission needed).
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                })
                if (uri != null) export(uri) else status.text = "Could not create $name in Downloads."
            } else status.text = "No file picker on this phone to save the mix."
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "track"

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_VOCAL, PICK_BACKING -> load(uri, requestCode == PICK_VOCAL)
            EXPORT -> export(uri)
        }
    }

    private fun load(uri: Uri, isVocal: Boolean) {
        val name = displayName(uri)
        val strip = strips[if (isVocal) 0 else 1]!!
        engine.pause(); playBtn.text = "▶  Play"
        status.text = "Reading $name …"
        thread(name = "decode") {
            try {
                var shown = -1   // one UI update per percent, not one per compressed frame
                val t = Decoder.decode(this, uri, name) { p ->
                    val pc = (p * 100).toInt()
                    if (pc != shown) { shown = pc; ui.post { status.text = "Reading $name … $pc %" } }
                }
                if (isVocal) engine.vocal = t else engine.backing = t
                ui.post {
                    strip.name.text = "$name · ${Mix.clock(t.frames)} · ${t.sourceRate / 1000.0} kHz"
                    status.text = "Ready. " + if (engine.vocal != null && engine.backing != null) "Press Play." else "Load the other track."
                }
            } catch (e: Throwable) {
                ui.post { status.text = "Could not read $name: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun export(uri: Uri) {
        status.text = "Exporting …"
        thread(name = "export") {
            try {
                val red = contentResolver.openOutputStream(uri)!!.use { out ->
                    var shown = -1
                    engine.export(out.buffered(1 shl 16)) { p ->
                        val pc = (p * 100).toInt()
                        if (pc != shown) { shown = pc; ui.post { status.text = "Exporting … $pc %" } }
                    }
                }
                val note = if (red < -0.1) " The limiter pulled peaks down by up to %.1f dB — lower a fader if that is too much.".format(-red) else ""
                ui.post { status.text = "Exported ${displayName(uri)} (48 kHz, 16-bit WAV).$note" }
            } catch (e: Throwable) {
                ui.post { status.text = "Export failed: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    override fun onPause() { super.onPause(); if (!isChangingConfigurations) { engine.pause(); playBtn.text = "▶  Play" } }
}
