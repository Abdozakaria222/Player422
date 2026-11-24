package com.abdo.player422

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live-TV player that always decodes video in SOFTWARE (FFmpeg/avcodec),
 * so 4:2:2 feeds (MPEG-2 422P / H.264 High 4:2:2) play on boxes whose
 * hardware decoder rejects them.
 */
class MainActivity : Activity() {

    companion object {
        const val PREFS = "player422"
        const val KEY_SOURCE = "source"
        const val KEY_CHANNELS = "channels"
        const val KEY_INDEX = "index"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_DEINT = "deinterlace"
        const val KEY_HW = "hw_fallback"
    }

    data class Channel(val name: String, val url: String)

    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var info: TextView
    private lateinit var list: ListView

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private var channels = mutableListOf<Channel>()
    private var index = 0
    private var openedFd: ParcelFileDescriptor? = null
    private var retries = 0

    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val retry = Runnable { playCurrent() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        createPlayer()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(retry)
        player?.stop()
    }

    override fun onRestart() {
        super.onRestart()
        playCurrent()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoLayout = VLCVideoLayout(this)
        root.addView(videoLayout, FrameLayout.LayoutParams(-1, -1))

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(0xAA000000.toInt())
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(40, 40, 40, 40)
        })

        list = ListView(this).apply {
            setBackgroundColor(0xE0101010.toInt())
            visibility = View.GONE
            isFocusable = true
            setOnItemClickListener { _, _, pos, _ ->
                visibility = View.GONE
                index = pos
                playCurrent()
            }
        }
        root.addView(list, FrameLayout.LayoutParams(600, -1, Gravity.START))
        setContentView(root)
    }

    private fun showInfo(text: String, ms: Long = 4000) {
        info.text = text
        info.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        if (ms > 0) ui.postDelayed(hideInfo, ms)
    }

    private fun refreshList() {
        list.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1,
            channels.mapIndexed { i, c -> "${i + 1}. ${c.name}" }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.textSize = 20f
                return v
            }
        }
    }

    // ---------------------------------------------------------------- player

    private fun createPlayer() {
        val opts = arrayListOf(
            "--avcodec-hw=none",          // force software decoding (4:2:2 safe)
            "--avcodec-threads=0",        // use all CPU cores
            "--avcodec-skiploopfilter=0",
            "--network-caching=1500",
            "--live-caching=1500",
            "--clock-jitter=0",
            "--drop-late-frames",
            "--skip-frames",
            "--no-stats"
        )
        if (prefs.getBoolean(KEY_DEINT, true)) {
            opts += "--deinterlace=1"
            opts += "--deinterlace-mode=yadif"
        }
        val vlc = LibVLC(this, opts)
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    retries = 0
                    current()?.let { showInfo("▶ ${it.name}\nSoftware decode (4:2:2)") }
                }
                MediaPlayer.Event.EncounteredError,
                MediaPlayer.Event.EndReached -> scheduleRetry()
            }
        }
        libVLC = vlc
        player = mp
    }

    private fun releasePlayer() {
        ui.removeCallbacksAndMessages(null)
        player?.let { it.stop(); it.detachViews(); it.release() }
        libVLC?.release()
        player = null
        libVLC = null
        openedFd?.close()
        openedFd = null
    }

    private fun current(): Channel? = channels.getOrNull(index)

    private fun playCurrent() {
        val ch = current() ?: return
        val vlc = libVLC ?: return
        val mp = player ?: return
        ui.removeCallbacks(retry)
        prefs.edit().putInt(KEY_INDEX, index).apply()
        showInfo("${index + 1}. ${ch.name}\nجاري التشغيل…", 0)

        openedFd?.close()
        openedFd = null
        val uri = Uri.parse(ch.url)
        val media = try {
            if (uri.scheme == "content") {
                val fd = contentResolver.openFileDescriptor(uri, "r")!!
                openedFd = fd
                Media(vlc, fd.fileDescriptor)
            } else {
                Media(vlc, uri)
            }
        } catch (e: Exception) {
            showInfo("تعذر فتح الرابط:\n${e.message}")
            return
        }
        // Software decoding on; fall back to HW only if the user enabled it.
        val hw = prefs.getBoolean(KEY_HW, false)
        media.setHWDecoderEnabled(hw, false)
        media.addOption(":network-caching=1500")
        mp.media = media
        media.release()
        mp.play()
    }

    private fun scheduleRetry() {
        retries++
        val delay = if (retries < 5) 2000L else 8000L
        showInfo("انقطع البث… إعادة المحاولة (${retries})", delay)
        ui.removeCallbacks(retry)
        ui.postDelayed(retry, delay)
    }

    private fun zap(step: Int) {
        if (channels.isEmpty()) return
        index = (index + step + channels.size) % channels.size
        retries = 0
        playCurrent()
    }

    // ---------------------------------------------------------------- sources

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent != null && data != null && intent.action == Intent.ACTION_VIEW) {
            // Opened as "external player" from another app: play immediately.
            val title = intent.getStringExtra("title")
                ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                ?: data.lastPathSegment ?: "Live"
            channels = mutableListOf(Channel(title, data.toString()))
            index = 0
            refreshList()
            playCurrent()
            return
        }
        // Launched normally: resume last channel automatically.
        channels = loadSavedChannels()
        if (channels.isEmpty()) {
            showSettings()
        } else {
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, channels.size - 1)
            refreshList()
            playCurrent()
            // refresh the playlist in background in case it changed
            prefs.getString(KEY_SOURCE, null)?.let { loadSource(it, silent = true) }
        }
    }

    private fun loadSavedChannels(): MutableList<Channel> =
        (prefs.getString(KEY_CHANNELS, "") ?: "").lines()
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size == 2 && p[1].isNotBlank()) Channel(p[0], p[1]) else null
            }.toMutableList()

    private fun saveChannels(list: List<Channel>) {
        prefs.edit().putString(
            KEY_CHANNELS,
            list.joinToString("\n") { "${it.name.replace('\t', ' ')}\t${it.url}" }
        ).apply()
    }

    /** Source can be an M3U playlist URL or a single stream URL (http/udp/rtsp/…). */
    private fun loadSource(src: String, silent: Boolean = false) {
        if (!silent) showInfo("جاري تحميل القنوات…", 0)
        Thread {
            val parsed = fetchPlaylist(src)
            ui.post {
                val result = parsed ?: listOf(Channel("قناة", src))
                if (silent && result == channels) return@post
                if (silent && parsed == null) return@post
                val keepUrl = current()?.url
                channels = result.toMutableList()
                saveChannels(channels)
                refreshList()
                if (silent) {
                    index = channels.indexOfFirst { it.url == keepUrl }.takeIf { it >= 0 } ?: index
                    if (current()?.url != keepUrl) { index = 0; playCurrent() }
                } else {
                    index = 0
                    playCurrent()
                }
            }
        }.start()
    }

    /** Returns channels if [src] is an IPTV M3U list, or null if it's a direct stream. */
    private fun fetchPlaylist(src: String): List<Channel>? {
        if (!src.startsWith("http", ignoreCase = true) && !src.startsWith("file")) return null
        return try {
            val text = if (src.startsWith("file")) {
                java.io.File(Uri.parse(src).path!!).readText()
            } else {
                val c = URL(src).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "Player422")
                val ct = c.contentType ?: ""
                if (ct.startsWith("video/") && !ct.contains("mpegurl")) { c.disconnect(); return null }
                c.inputStream.use { s ->
                    val buf = ByteArray(2_000_000)
                    var n = 0
                    while (n < buf.size) {
                        val r = s.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(buf, 0, n, Charsets.UTF_8)
                }
            }
            // HLS stream playlists contain #EXT-X- tags -> treat as a direct stream.
            if (!text.contains("#EXTINF") || text.contains("#EXT-X-")) return null
            parseM3u(text).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> name = line.substringAfterLast(',').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    out += Channel(name?.ifBlank { null } ?: "قناة ${out.size + 1}", line)
                    name = null
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val url = EditText(this).apply {
            hint = "رابط القناة أو قائمة m3u (http / udp / rtsp)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_SOURCE, ""))
        }
        val auto = CheckBox(this).apply {
            text = "تشغيل تلقائي مع فتح الريسيفر"
            isChecked = prefs.getBoolean(KEY_AUTOSTART, false)
        }
        val deint = CheckBox(this).apply {
            text = "Deinterlace (لقنوات 1080i)"
            isChecked = prefs.getBoolean(KEY_DEINT, true)
        }
        val hw = CheckBox(this).apply {
            text = "السماح بالـ Hardware (لو القناة مش 4:2:2 وبتقطع)"
            isChecked = prefs.getBoolean(KEY_HW, false)
        }
        box.addView(url); box.addView(auto); box.addView(deint); box.addView(hw)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Player 422 – الإعدادات")
            .setView(box)
            .setPositiveButton("حفظ وتشغيل") { _, _ ->
                val deintChanged = deint.isChecked != prefs.getBoolean(KEY_DEINT, true)
                prefs.edit()
                    .putBoolean(KEY_AUTOSTART, auto.isChecked)
                    .putBoolean(KEY_DEINT, deint.isChecked)
                    .putBoolean(KEY_HW, hw.isChecked)
                    .apply()
                if (deintChanged) { releasePlayer(); createPlayer() }
                val src = url.text.toString().trim()
                if (src.isNotEmpty() && src != prefs.getString(KEY_SOURCE, null)) {
                    prefs.edit().putString(KEY_SOURCE, src).apply()
                    loadSource(src)
                } else {
                    playCurrent()
                }
            }
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("فحص الجهاز") { _, _ -> showDeviceCheck() }
            .show()
    }

    private fun showDeviceCheck() {
        val tv = TextView(this).apply {
            text = "جاري الفحص…"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(40, 24, 40, 24)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv); isFocusable = true }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("فحص الجهاز – صوّر الشاشة دي")
            .setView(scroll)
            .setPositiveButton("تم", null)
            .setNeutralButton("فحص Root", null)
            .show().also { d ->
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    tv.text = "جاري الفحص بالـ Root… لو ظهرت رسالة سماح، دوس سماح"
                    Thread {
                        val r = DeviceCheck.runRoot()
                        ui.post { tv.text = r; scroll.scrollTo(0, 0) }
                    }.start()
                }
            }
        scroll.requestFocus()
        Thread {
            val r = try { DeviceCheck.run(this) } catch (e: Exception) { "خطأ: ${e.message}" }
            ui.post { tv.text = r }
        }.start()
    }

    // ---------------------------------------------------------------- remote keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (list.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { list.visibility = View.GONE; return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (channels.isNotEmpty()) {
                    list.visibility = View.VISIBLE
                    list.requestFocus()
                    list.setSelection(index)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_DPAD_LEFT -> { showSettings(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                current()?.let { showInfo("${index + 1}. ${it.name}\n${it.url}") }
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val n = keyCode - KeyEvent.KEYCODE_0
            if (n in 1..channels.size) { index = n - 1; playCurrent() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
