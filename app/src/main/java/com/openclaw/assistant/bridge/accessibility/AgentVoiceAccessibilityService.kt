package com.openclaw.assistant.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.openclaw.assistant.bridge.WakeLockManager
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Accessibility Bridge — the agent reads the visible screen and acts on it:
 * tap, type, swipe, screenshots, clipboard, media, notifications.
 *
 * The user must explicitly enable WakeHermesClaw in Android Settings →
 * Accessibility before any of the screen.* capabilities can run. The service
 * is intentionally a thin "remote control" — it has no auto-behaviour, no
 * background scraping, no event-stream broadcasting. It only acts when a
 * capability invocation reaches it.
 *
 * This service is exposed only on sideload builds (`BuildConfig.IS_SIDELOAD`)
 * because Google Play restricts accessibility services that are not assistive
 * to disabled users; the Play track keeps the manifest entry but the
 * AccessibilityCapabilities advertise `isAvailable = false` to keep the bridge
 * honest.
 */
class AgentVoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op: pull-driven service */ }

    fun performTap(x: Float, y: Float): Boolean {
        return WakeLockManager.withWakeLock {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            dispatchAndWait(GestureDescription.Builder().addStroke(stroke).build(), 1500L)
        }
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        return WakeLockManager.withWakeLock {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val duration = durationMs.coerceAtLeast(50L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            dispatchAndWait(GestureDescription.Builder().addStroke(stroke).build(), duration + 1500L)
        }
    }

    fun performLongPress(x: Float, y: Float, durationMs: Long): Boolean {
        return WakeLockManager.withWakeLock {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val duration = durationMs.coerceAtLeast(350L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            dispatchAndWait(GestureDescription.Builder().addStroke(stroke).build(), duration + 1500L)
        }
    }

    /**
     * dispatchGesture 必须从主线程发起，而且它的返回值只代表"请求被接受"，手势真正执行完是回调里才通知的。
     * 之前这两点都没处理：桥的 execute() 跑在 IO 线程上，于是手势在真机上静默失效（返回 ok、界面没反应）。
     * 这里 post 到主线程、等 onCompleted/onCancelled，超时按失败处理。
     */
    private fun dispatchAndWait(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val accepted = runCatching {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                }, null)
            }.getOrDefault(false)
            if (!accepted) latch.countDown()
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return completed.get()
    }

    /** Outcome of a screen grab: a bitmap on success, else the platform error code (-1 = call threw). */
    class ScreenGrab(val bitmap: Bitmap?, val errorCode: Int) {
        val ok: Boolean get() = bitmap != null
    }

    /**
     * Grab the current screen through `AccessibilityService.takeScreenshot()` (API 30+,
     * requires `android:canTakeScreenshot="true"` in the service config).
     *
     * The platform is asynchronous and rate-limited: this suspends until the callback
     * fires and hands back either a software bitmap (copied out of the HardwareBuffer,
     * which is then closed) or the platform error code so the caller can report a
     * truthful reason instead of a generic failure.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenBitmap(): ScreenGrab = suspendCancellableCoroutine { cont ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = runCatching {
                    Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                runCatching { screenshot.hardwareBuffer.close() }
                if (cont.isActive) cont.resume(ScreenGrab(bitmap, 0))
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(ScreenGrab(null, errorCode))
            }
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), callback)
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(ScreenGrab(null, -1))
        }
    }

    fun readScreenSnapshot(): ScreenSnapshot {
        val roots = snapshotRoots()
        return try {
            ScreenSnapshot.fromRoots(roots)
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    fun findNodes(text: String?, className: String?, clickable: Boolean?, limit: Int): List<ScreenNodeSnapshot> {
        val needle = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val klass = className?.trim()?.takeIf { it.isNotEmpty() }
        return readScreenSnapshot().nodes.filter { node ->
            val hay = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
            (needle == null || needle in hay) &&
                (klass == null || node.className == klass) &&
                (clickable == null || node.clickable == clickable)
        }.take(limit.coerceIn(1, 50))
    }

    fun describeNode(nodeId: String): ScreenNodeSnapshot? =
        readScreenSnapshot().nodes.firstOrNull { it.nodeId == nodeId }

    fun screenHash(): ScreenHash {
        val snapshot = readScreenSnapshot()
        return ScreenHash(
            hash = snapshot.computeHash(),
            nodeCount = snapshot.nodes.size,
            truncated = snapshot.truncated,
        )
    }

    /**
     * 窗口顺序决定了 packageName 取谁、节点先遍历谁。`windows` 的顺序不保证把前台窗口放前面
     * —— 实测在这台 ROM 上第一条是状态栏窗口，于是 screen.window.describe 会把
     * packageName 报成 com.android.systemui / "主屏幕"，哪怕用户正在别的应用里。
     * 这里按 isActive 优先重排，再退回 windowId 顺序。
     */
    private fun snapshotRoots(): List<AccessibilityNodeInfo> {
        val ordered = runCatching {
            windows.sortedByDescending { runCatching { it.isActive }.getOrDefault(false) }
        }.getOrDefault(emptyList())
        val multi = ordered.mapNotNull { runCatching { it.root }.getOrNull() }
        return if (multi.isNotEmpty()) multi else listOfNotNull(rootInActiveWindow)
    }

    companion object {
        @Volatile private var instance: AgentVoiceAccessibilityService? = null
        fun get(): AgentVoiceAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null
    }
}

data class ScreenSnapshot(
    val packageName: String?,
    val rootBounds: BoundsSnapshot,
    val nodes: List<ScreenNodeSnapshot>,
    val truncated: Boolean = false,
) {
    fun computeHash(): String {
        val joined = nodes.joinToString("\u001e") { node ->
            listOf(
                node.className.orEmpty(),
                node.text.orEmpty(),
                node.contentDescription.orEmpty(),
                node.viewId.orEmpty(),
                "${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}",
            ).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_NODES = 512
        fun fromRoots(roots: List<AccessibilityNodeInfo>): ScreenSnapshot {
            val nodes = mutableListOf<ScreenNodeSnapshot>()
            var truncated = false
            var packageName: String? = null
            val union = Rect()
            var unionSet = false
            roots.forEachIndexed { windowIndex, root ->
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    return@forEachIndexed
                }
                if (packageName == null) packageName = root.packageName?.toString()
                val rect = Rect()
                root.getBoundsInScreen(rect)
                if (!unionSet) {
                    union.set(rect)
                    unionSet = true
                } else {
                    union.union(rect)
                }
                walk(root, windowIndex, nodes)
                if (nodes.size >= MAX_NODES) truncated = true
            }
            return ScreenSnapshot(
                packageName = packageName,
                rootBounds = union.toBoundsSnapshot(),
                nodes = nodes.take(MAX_NODES),
                truncated = truncated,
            )
        }

        private fun walk(node: AccessibilityNodeInfo?, windowIndex: Int, out: MutableList<ScreenNodeSnapshot>) {
            if (node == null || out.size >= MAX_NODES) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
            val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
            val interesting = (text != null || desc != null || node.isClickable || node.isLongClickable || node.isScrollable || node.isEditable) &&
                rect.width() > 0 && rect.height() > 0
            if (interesting) {
                out += ScreenNodeSnapshot(
                    nodeId = "w$windowIndex:${out.size}",
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                    bounds = rect.toBoundsSnapshot(),
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                )
            }
            for (i in 0 until node.childCount) {
                if (out.size >= MAX_NODES) return
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, windowIndex, out)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }
    }
}

data class ScreenNodeSnapshot(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val bounds: BoundsSnapshot,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
)

data class BoundsSnapshot(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class ScreenHash(val hash: String, val nodeCount: Int, val truncated: Boolean)

private fun Rect.toBoundsSnapshot(): BoundsSnapshot = BoundsSnapshot(left, top, right, bottom)
