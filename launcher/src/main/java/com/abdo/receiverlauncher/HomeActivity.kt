package com.abdo.receiverlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/**
 * Simple home screen for the T21 PRO MAX receiver:
 *  - big "القنوات" tile that opens the receiver's satellite TV app
 *  - opens the channels app automatically once after every boot
 *  - favorites row + all apps grid, remote (D-pad) friendly
 */
class HomeActivity : Activity() {

    companion object {
        const val PREFS = "launcher"
        const val KEY_CHANNELS_PKG = "channels_pkg"
        const val KEY_AUTO_CHANNELS = "auto_channels"
        const val KEY_FAVS = "favorites"
        const val KEY_LAST_BOOT = "last_boot"
        const val DEFAULT_CHANNELS_PKG = "com.tiartoptv.dvb"
        private const val ACCENT = 0xFF29B6F6.toInt()
        private const val BG = 0xFF0B1622.toInt()
        private const val TILE = 0xFF16283A.toInt()
    }

    data class App(val pkg: String, val label: String, val icon: Drawable?, val banner: Drawable?)

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private var apps = listOf<App>()
    private var focusedPkg: String? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }
        scroll.addView(content)
        setContentView(scroll)
        if (!prefs.contains(KEY_FAVS)) initDefaultFavorites()
    }

    override fun onResume() {
        super.onResume()
        build()
        maybeAutoOpenChannels()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump focus back to the channels tile
        content.findViewWithTag<View>("channels")?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* launcher: stay here */ }

    // ------------------------------------------------------------ auto open after boot

    private fun maybeAutoOpenChannels() {
        if (!prefs.getBoolean(KEY_AUTO_CHANNELS, true)) return
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (!prefs.contains(KEY_LAST_BOOT)) {           // first run: don't jump away immediately
            prefs.edit().putLong(KEY_LAST_BOOT, bootTime).apply(); return
        }
        val last = prefs.getLong(KEY_LAST_BOOT, 0L)
        if (kotlin.math.abs(bootTime - last) < 60_000) return   // already done this boot
        prefs.edit().putLong(KEY_LAST_BOOT, bootTime).apply()
        content.postDelayed({ openChannels(silent = true) }, 1500)
    }

    // ------------------------------------------------------------ UI

    private fun build() {
        apps = loadApps()
        content.removeAllViews()

        // header: clock + date + settings
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE d MMMM"
            format24Hour = "EEEE d MMMM"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 18f
            setPadding(dp(20), 0, 0, 0)
        }
        header.addView(clock)
        header.addView(date)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(smallButton("⚙ الإعدادات") { showSettings() })
        content.addView(header)
        if (!DateFormat.is24HourFormat(this)) clock.format12Hour = "h:mm a"

        // big channels tile + favorites
        content.addView(sectionTitle("المفضلة"))
        val favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val channelsTile = channelsTile()
        favRow.addView(channelsTile)
        favorites().forEach { pkg ->
            apps.firstOrNull { it.pkg == pkg }?.let { favRow.addView(appTile(it, big = true)) }
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(favRow)
        })

        // all apps
        content.addView(sectionTitle("كل التطبيقات"))
        val grid = GridLayout(this).apply { columnCount = 6 }
        apps.forEach { grid.addView(appTile(it, big = false)) }
        content.addView(grid)

        content.addView(TextView(this).apply {
            text = "زرار MENU أو ضغطة طويلة على OK: إضافة للمفضلة / إزالة"
            setTextColor(0xFF78909C.toInt())
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        })

        // restore focus
        val restore = focusedPkg?.let { content.findViewWithTag<View>("app:$it") }
        (restore ?: channelsTile).requestFocus()
    }

    private fun channelsTile(): View {
        val pkg = channelsPkg()
        val app = apps.firstOrNull { it.pkg == pkg }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = "channels"
            background = tileBg(0xFF0D47A1.toInt())
            isFocusable = true
            isClickable = true
            setOnClickListener { openChannels() }
            setOnLongClickListener { showSettings(); true }
            onFocusChangeListener = scaleOnFocus
        }
        box.addView(ImageView(this).apply {
            setImageDrawable(app?.icon ?: getDrawable(android.R.drawable.ic_media_play))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        box.addView(TextView(this).apply {
            text = "القنوات"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = app?.label ?: "اختار تطبيق القنوات من الإعدادات"
            setTextColor(0xFFBBDEFB.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        })
        box.layoutParams = LinearLayout.LayoutParams(dp(300), dp(170)).apply {
            setMargins(0, dp(8), dp(18), dp(8))
        }
        return box
    }

    private fun appTile(app: App, big: Boolean): View {
        val w = if (big) dp(240) else dp(160)
        val h = if (big) dp(170) else dp(120)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = if (big) "fav:${app.pkg}" else "app:${app.pkg}"
            background = tileBg(TILE)
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { launch(app.pkg) }
            setOnLongClickListener { appMenu(app); true }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                scaleOnFocus.onFocusChange(v, has)
                if (has) focusedPkg = app.pkg
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(app.banner ?: app.icon)
        }
        if (app.banner != null) {
            box.addView(img, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val s = if (big) dp(80) else dp(56)
            box.addView(img, LinearLayout.LayoutParams(s, s))
        }
        box.addView(TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = if (big) 17f else 14f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        box.layoutParams = if (big) {
            LinearLayout.LayoutParams(w, h).apply { setMargins(0, dp(8), dp(18), dp(8)) }
        } else {
            GridLayout.LayoutParams().apply {
                width = w; height = h
                setMargins(0, dp(8), dp(16), dp(8))
            }
        }
        return box
    }

    private val scaleOnFocus = View.OnFocusChangeListener { v, has ->
        val s = if (has) 1.08f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        v.elevation = if (has) dp(8).toFloat() else 0f
    }

    private fun tileBg(color: Int): Drawable {
        fun shape(stroke: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
            if (stroke != 0) setStroke(dp(3), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(ACCENT))
            addState(intArrayOf(android.R.attr.state_pressed), shape(ACCENT))
            addState(intArrayOf(), shape(0))
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFFECEFF1.toInt())
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun smallButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 17f
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = tileBg(TILE)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ apps

    private fun loadApps(): List<App> {
        val pm = packageManager
        val found = LinkedHashMap<String, App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            pm.queryIntentActivities(q, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || found.containsKey(pkg)) return@forEach
                val banner = try { ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm) } catch (_: Exception) { null }
                found[pkg] = App(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), banner)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun launch(pkg: String) {
        val i = launchIntent(pkg)
        if (i == null) { toast("التطبيق مش موجود"); return }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { toast("تعذر فتح التطبيق") }
    }

    private fun channelsPkg(): String =
        prefs.getString(KEY_CHANNELS_PKG, null) ?: DEFAULT_CHANNELS_PKG

    private fun openChannels(silent: Boolean = false) {
        val pkg = channelsPkg()
        if (launchIntent(pkg) == null) {
            if (!silent) { toast("اختار تطبيق القنوات"); pickChannelsApp() }
            return
        }
        launch(pkg)
    }

    // ------------------------------------------------------------ favorites

    private fun favorites(): List<String> =
        (prefs.getString(KEY_FAVS, "") ?: "").split(',').filter { it.isNotBlank() }

    private fun setFavorites(list: List<String>) =
        prefs.edit().putString(KEY_FAVS, list.distinct().joinToString(",")).apply()

    private fun initDefaultFavorites() {
        val wanted = listOf(
            "com.abdo.player422", "com.google.android.youtube.tv",
            "com.mbm_soft.ultraiptv", "com.mbm_soft.aroma4kitv", "com.mxtech.videoplayer.ad"
        )
        setFavorites(wanted.filter { launchIntent(it) != null })
    }

    private fun appMenu(app: App) {
        val isFav = app.pkg in favorites()
        val items = arrayOf(
            if (isFav) "إزالة من المفضلة" else "إضافة للمفضلة",
            "تحريك لأول المفضلة",
            "اعتباره تطبيق القنوات",
            "معلومات التطبيق"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setFavorites(if (isFav) favorites() - app.pkg else favorites() + app.pkg)
                    1 -> setFavorites(listOf(app.pkg) + (favorites() - app.pkg))
                    2 -> prefs.edit().putString(KEY_CHANNELS_PKG, app.pkg).apply()
                    3 -> try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${app.pkg}")))
                    } catch (_: Exception) { toast("غير متاح") }
                }
                build()
            }
            .show()
    }

    // ------------------------------------------------------------ settings

    private fun showSettings() {
        val auto = prefs.getBoolean(KEY_AUTO_CHANNELS, true)
        val current = apps.firstOrNull { it.pkg == channelsPkg() }?.label ?: channelsPkg()
        val items = arrayOf(
            (if (auto) "✓ " else "✗ ") + "فتح القنوات تلقائي مع تشغيل الريسيفر",
            "تطبيق القنوات: $current",
            "جعل اللانشر ده الشاشة الرئيسية",
            "إعدادات أندرويد",
            "فتح القنوات دلوقتي"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("إعدادات اللانشر")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { prefs.edit().putBoolean(KEY_AUTO_CHANNELS, !auto).apply(); showSettings() }
                    1 -> pickChannelsApp()
                    2 -> openHomeSettings()
                    3 -> try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { toast("غير متاح") }
                    4 -> openChannels()
                }
            }
            .show()
    }

    private fun pickChannelsApp() {
        val list = apps
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("اختار تطبيق القنوات")
            .setItems(list.map { "${it.label}  (${it.pkg})" }.toTypedArray()) { _, i ->
                prefs.edit().putString(KEY_CHANNELS_PKG, list[i].pkg).apply()
                build()
            }
            .show()
    }

    private fun openHomeSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
        toast("افتح الإعدادات ← التطبيقات ← التطبيقات التلقائية ← الشاشة الرئيسية")
    }

    // ------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val tag = currentFocus?.tag as? String
            val pkg = tag?.substringAfter(':')
            val app = apps.firstOrNull { it.pkg == pkg }
            if (app != null) appMenu(app) else showSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ utils

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
