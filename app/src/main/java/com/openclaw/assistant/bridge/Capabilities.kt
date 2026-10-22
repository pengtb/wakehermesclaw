package com.openclaw.assistant.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Built-in Mobile Bridge capabilities. Only low/medium-risk read-only ones are
 * implemented here today; high-risk write actions (sms.send, camera.capture_photo,
 * contacts.* writes etc.) are intentionally left out — the manifest reflects what
 * is actually implementable, and `/execute` returns `unsupported_capability` for
 * everything else.
 */
object DeviceInfoCapability : BridgeCapability {
    override val name = "device.info"
    override val description = "Basic device identifiers and OS version"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("appPackage", context.packageName)
    }
}

object AppsListCapability : BridgeCapability {
    override val name = "apps.list"
    override val description = "List installed apps that have a launcher entry (label + package), with a system flag"
    override val group = "apps"
    override val riskLevel = RiskLevel.LOW

    /**
     * 2026-10 实测：这台 Android 16 机器上 getInstalledApplications() / getInstalledPackages()
     * 即使拿到 QUERY_ALL_PACKAGES（检查过 granted=true）也只返回自己一个 —— 系统侧把应用清单
     * 读接口过滤掉了；而 queryIntentActivities() 在 manifest 的 <queries> 声明了
     * MAIN/LAUNCHER 之后能正常拿到全部带桌面入口的应用（实测 358 个 activity）。
     *
     * 所以这里不再依赖枚举所有包，改为按桌面入口汇总、按包名去重：这正是"手机上装了哪些
     * 应用"的可用集合，也是 apps.launch 能用的包名来源。没有桌面入口的纯服务/Provider
     * 包不在其中（它们既点不开也没法启动）。
     */
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val pm = context.packageManager
        val includeSystem = arguments["include_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolveInfos = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())

        // 包名 -> (label, isSystem)
        val byPackage = LinkedHashMap<String, Pair<String, Boolean>>()
        resolveInfos.forEach { ri ->
            val info = ri.activityInfo.applicationInfo ?: return@forEach
            val pkg = info.packageName
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!includeSystem && isSystem) return@forEach
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(pkg)
            byPackage[pkg] = label to isSystem
        }

        val rows = byPackage.entries
            .map { (pkg, v) -> Row(pkg, v.first, v.second) }
            .sortedBy { it.label.lowercase() }

        val apps = buildJsonArray {
            rows.forEach { r ->
                add(buildJsonObject {
                    put("packageName", r.packageName)
                    put("label", r.label)
                    put("system", r.system)
                    put("launchable", true)
                })
            }
        }
        return buildJsonObject {
            put("apps", apps)
            put("count", rows.size)
            put("systemCount", rows.count { it.system })
            // 诊断用：桌面入口的 activity 数量（去重前）
            put("launcherActivities", resolveInfos.size)
        }
    }

    private data class Row(val packageName: String, val label: String, val system: Boolean)
}

object ClipboardReadCapability : BridgeCapability {
    override val name = "clipboard.read"
    override val description = "Read current clipboard text"
    override val group = "clipboard.read"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return buildJsonObject { put("text", text) }
    }
}

object DefaultCapabilities {
    val all: List<BridgeCapability> = listOf(
        DeviceInfoCapability,
        AppsListCapability,
        ClipboardReadCapability,
    )
}
