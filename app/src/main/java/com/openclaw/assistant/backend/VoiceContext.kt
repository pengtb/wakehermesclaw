package com.openclaw.assistant.backend

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.openclaw.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 语音对话时附带给模型的一段"当前环境"。
 *
 * 为什么需要：语音链路里用户经常问"现在几点""我在哪""手机还有多少电""有没有网"这类问题，
 * 而这些只有手机自己知道。以前模型只能反问或者瞎猜，现在每次请求都把这几项实时读出来放进去，
 * 它在需要时自然用上就行。
 *
 * 全部是尽力而为：定位没授权、Geocoder 没网、系统读不到，都只是少一行，不影响对话。
 */
object VoiceContext {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.CHINA)

    suspend fun snapshot(context: Context): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()

        // 时间：带时区和星期，避免模型按 UTC 或训练语料里的日期回答
        val now = runCatching { ZonedDateTime.now() }.getOrNull()
        if (now != null) {
            lines += "时间：${now.format(TIME_FORMAT)}（${now.zone.id}，UTC${now.offset.id.replace("Z", "+0")}）"
        }

        locationLine(context)?.let { lines += it }
        batteryLine(context)?.let { lines += it }
        networkLine(context)?.let { lines += it }
        screenLine(context)?.let { lines += it }
        lines += "设备：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}），" +
            "应用版本 ${BuildConfig.VERSION_NAME}"

        if (lines.isEmpty()) "" else buildString {
            appendLine("当前环境（由手机实时读取，和用户此刻所处的时空一致；与问题相关时直接用，不用反问，无关时不必提）：")
            lines.forEach { appendLine("- $it") }
        }.trim()
    }

    @SuppressLint("MissingPermission")
    private fun locationLine(context: Context): String? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull() ?: return "位置：读不到（可能没授权或刚开机，尚未定位）"

        val place = reverseGeocode(context, loc.latitude, loc.longitude)
        val coord = String.format(Locale.US, "%.3f,%.3f", loc.latitude, loc.longitude)
        return if (place != null) {
            "位置：$place（约 $coord，精度 ${loc.accuracy.toInt()} 米）"
        } else {
            "位置：约 $coord（精度 ${loc.accuracy.toInt()} 米，未能解析成地名）"
        }
    }

    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? = runCatching {
        @Suppress("DEPRECATION")
        val results = Geocoder(context, Locale.CHINA).getFromLocation(lat, lon, 1)
        val addr = results?.firstOrNull() ?: return@runCatching null
        listOfNotNull(
            addr.locality ?: addr.subAdminArea ?: addr.adminArea,
            addr.subLocality,
            addr.thoroughfare,
        ).distinct().joinToString("").ifBlank { null }
    }.getOrNull()

    private fun batteryLine(context: Context): String? {
        val intent: Intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) "电量：$pct%（正在充电）" else "电量：$pct%（未充电）"
    }

    private fun networkLine(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "网络：未知"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return "网络：未联网"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其它网络"
        }
        return "网络：$type"
    }

    private fun screenLine(context: Context): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return if (pm.isInteractive) "屏幕：亮着（用户正在看手机）" else "屏幕：已熄灭"
    }
}
