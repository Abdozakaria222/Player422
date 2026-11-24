package com.abdo.player422

import android.content.Context
import android.media.tv.TvInputManager
import android.os.Build
import java.io.File

/** Collects what we need to know to reach the satellite tuner, without ADB. */
object DeviceCheck {

    fun run(ctx: Context): String {
        val sb = StringBuilder()

        sb.appendLine("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("أندرويد: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        sb.appendLine("المعالج: ${prop("ro.board.platform")} | ${prop("ro.hardware")} | ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        // 1) tuner device nodes (Linux DVB API)
        sb.appendLine("【التيونر /dev/dvb】")
        val nodes = mutableListOf<File>()
        File("/dev").listFiles()?.filter { it.name.startsWith("dvb") }?.forEach { f ->
            if (f.isDirectory) f.walk().filter { it.isFile || !it.isDirectory }.forEach { nodes += it }
            else nodes += f
        }
        File("/dev/dvb").takeIf { it.isDirectory }?.walk()?.filter { !it.isDirectory }?.forEach {
            if (it !in nodes) nodes += it
        }
        if (nodes.isEmpty()) {
            val dvbDirExists = File("/dev/dvb").exists()
            sb.appendLine(if (dvbDirExists) "موجود بس مش مسموح بقراءته" else "مش ظاهر للتطبيقات")
        } else {
            nodes.take(20).forEach {
                sb.appendLine("${it.path}  قراءة:${yn(it.canRead())} كتابة:${yn(it.canWrite())}")
            }
        }
        sb.appendLine()

        // 2) root
        sb.appendLine("【Root】")
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/data/adb/magisk")
        val found = suPaths.filter { File(it).exists() }
        sb.appendLine(if (found.isEmpty()) "مفيش" else "موجود: ${found.joinToString()}")
        sb.appendLine("ro.debuggable=${prop("ro.debuggable")}  ro.secure=${prop("ro.secure")}  adb=${prop("persist.sys.usb.config")}")
        sb.appendLine()

        // 3) TV inputs registered by the receiver (TV Input Framework)
        sb.appendLine("【مداخل التلفزيون TV Input】")
        try {
            val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val inputs = tim?.tvInputList.orEmpty()
            if (inputs.isEmpty()) sb.appendLine("مفيش")
            inputs.forEach {
                sb.appendLine("${it.loadLabel(ctx)} | ${it.id} | type=${it.type}")
            }
        } catch (e: Exception) {
            sb.appendLine("تعذر: ${e.message}")
        }
        sb.appendLine()

        // 4) TV / DVB related apps
        sb.appendLine("【تطبيقات القنوات】")
        val keys = listOf("tv", "dvb", "dtv", "live", "sat", "channel", "player", "iptv", "stream", "dlna")
        val apps = ctx.packageManager.getInstalledApplications(0)
            .filter { a -> keys.any { a.packageName.contains(it, ignoreCase = true) } }
            .sortedBy { it.packageName }
        if (apps.isEmpty()) sb.appendLine("مفيش")
        apps.take(40).forEach {
            sb.appendLine("${ctx.packageManager.getApplicationLabel(it)}  →  ${it.packageName}")
        }

        return sb.toString()
    }

    /** Deep check using root (su). The receiver will ask to allow root – press Allow. */
    fun runRoot(): String {
        val script = """
            echo '== id =='; id
            echo '== tuner nodes =='
            ls -l /dev 2>/dev/null | grep -iE 'dvb|demux|dmx|tuner|front|tsi|tsp|tsin|goke|gk|gx|hi_|ca[0-9]|sci|smart|av'
            ls -lR /dev/dvb* 2>/dev/null
            echo '== props =='
            getprop | grep -iE 'dvb|tuner|goke|kunlun|tiar|frontend|demod' | head -30
            echo '== listening ports =='
            (netstat -tlnp 2>/dev/null || ss -tlnp 2>/dev/null) | head -40
            echo '== tv app procs =='
            ps -A 2>/dev/null | grep -iE 'tiar|dvb|goke|dlna|tv' | head -20
            echo '== tv app libs =='
            ls /data/app/*/com.tiartoptv.dvb*/lib/* 2>/dev/null | head -30
            ls /system/lib 2>/dev/null | grep -iE 'dvb|tuner|demux|frontend|goke|gk|tiar' | head -30
            echo '== selinux =='; getenforce
        """.trimIndent()
        return try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            t.start(); t.join(20000)
            p.destroy()
            out.toString().ifBlank { "مفيش رد من su (اتأكد إنك دوست سماح)" }
        } catch (e: Exception) {
            "su مش شغال: ${e.message}"
        }
    }

    private fun yn(b: Boolean) = if (b) "✓" else "✗"

    private fun prop(name: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, name) as String).ifBlank { "-" }
    } catch (_: Exception) { "-" }
}
