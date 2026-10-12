package com.willow.probe

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Feasibility probe for WeChat Channels (视频号). Answers three questions on a real phone:
 *  1. Which text (author, caption, hashtags, product card) is exposed to accessibility?
 *  2. Does takeScreenshot() return a real picture of the video (not a black frame)?
 *  3. Can the media stream be muted instantly from here?
 *
 * Everything goes to logcat under the tag "WillowProbe". On-screen text is only logged on
 * Channels ("finder") screens; other WeChat screens (chats, contacts) log class names only.
 *
 * Commands from the PC (adb shell am broadcast -p com.willow.probe -a <action>):
 *   com.willow.probe.DUMP     dump the current WeChat window tree
 *   com.willow.probe.SHOT     take a screenshot into the app's files dir
 *   com.willow.probe.MUTE     mute the media stream
 *   com.willow.probe.UNMUTE   unmute the media stream
 */
class ProbeService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity = "?"
    private var dumpCount = 0
    private var shotCount = 0
    private var contentChanges = 0
    private var lastTriggerAt = 0L

    private val autoDump = Runnable { dump("auto") }

    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUMP -> dump("manual")
                ACTION_SHOT -> screenshot()
                ACTION_MUTE -> setMuted(true)
                ACTION_UNMUTE -> setMuted(false)
            }
        }
    }

    override fun onServiceConnected() {
        running = true
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP); addAction(ACTION_SHOT); addAction(ACTION_MUTE); addAction(ACTION_UNMUTE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(commands, filter)
        log("connected · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(commands) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString() ?: return
                // Popups and dialogs also fire this event; only real activities change the screen.
                if (isActivity(event.packageName?.toString(), cls)) {
                    currentActivity = cls
                    log("── screen: $cls  (channels=${isChannels()})")
                    scheduleDump()
                } else {
                    log("   window: $cls")
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (!isChannels()) return
                val delta = if (Build.VERSION.SDK_INT >= 28) " dy=${event.scrollDeltaY}" else ""
                log("   scroll: ${short(event.className)} from=${event.fromIndex} to=${event.toIndex} " +
                    "items=${event.itemCount} y=${event.scrollY}$delta")
                scheduleDump()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!isChannels()) return
                log("   click: ${short(event.className)} text=${event.text} desc=${event.contentDescription}")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> contentChanges++
        }
    }

    /** Swipes fire many scroll events; dump once, after the feed has settled. */
    private fun scheduleDump() {
        lastTriggerAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(autoDump)
        handler.postDelayed(autoDump, SETTLE_MS)
    }

    private fun dump(reason: String) {
        val start = SystemClock.uptimeMillis()
        val withText = isChannels()
        dumpCount++
        log("════ DUMP #$dumpCount ($reason) screen=$currentActivity text=$withText " +
            "contentChanges=$contentChanges sinceTrigger=${start - lastTriggerAt}ms")
        contentChanges = 0

        var nodes = 0
        for (w in windows) {
            val root = w.root
            val pkg = root?.packageName
            log("  window type=${w.type} layer=${w.layer} active=${w.isActive} " +
                (if (Build.VERSION.SDK_INT >= 24) "title=${w.title} " else "") + "pkg=$pkg")
            if (root != null && pkg == WECHAT) nodes += walk(root, 0, withText)
        }
        log("════ END #$dumpCount  nodes=$nodes  took=${SystemClock.uptimeMillis() - start}ms")
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, withText: Boolean): Int {
        if (depth > MAX_DEPTH) return 0
        val b = Rect().also { node.getBoundsInScreen(it) }
        val sb = StringBuilder()
        sb.append("  ".repeat(depth)).append(short(node.className))
        node.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        if (withText) {
            node.text?.let { sb.append(" text=\"").append(clean(it)).append('"') }
            node.contentDescription?.let { sb.append(" desc=\"").append(clean(it)).append('"') }
        } else {
            if (node.text != null) sb.append(" text=<hidden>")
            if (node.contentDescription != null) sb.append(" desc=<hidden>")
        }
        sb.append(" [${b.left},${b.top}][${b.right},${b.bottom}]")
        if (!node.isVisibleToUser) sb.append(" offscreen")
        if (node.isClickable) sb.append(" click")
        if (node.isScrollable) sb.append(" scroll")
        if (node.isSelected) sb.append(" selected")
        log(sb.toString())

        var count = 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += walk(child, depth + 1, withText)
        }
        return count
    }

    private fun screenshot() {
        if (Build.VERSION.SDK_INT < 30) {
            log("SHOT: needs Android 11+ (this phone is API ${Build.VERSION.SDK_INT})")
            return
        }
        val start = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bmp == null) { log("SHOT: could not read the buffer"); return }
                shotCount++
                val file = File(getExternalFilesDir(null), "shot-$shotCount.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                log("SHOT ok: ${bmp.width}x${bmp.height} brightness=${brightness(bmp)}/255 " +
                    "took=${SystemClock.uptimeMillis() - start}ms → ${file.absolutePath}")
            }

            override fun onFailure(errorCode: Int) {
                log("SHOT failed: errorCode=$errorCode (3 = too soon after the last one, 2 = no permission)")
            }
        })
    }

    /** A black frame (FLAG_SECURE / DRM) shows up as brightness near 0. */
    private fun brightness(bmp: Bitmap): Int {
        var sum = 0L
        var n = 0
        for (y in 0 until bmp.height step 40) for (x in 0 until bmp.width step 40) {
            val p = bmp.getPixel(x, y)
            sum += ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)) / 3
            n++
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    private fun setMuted(mute: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val start = SystemClock.uptimeMillis()
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
        log("${if (mute) "MUTE" else "UNMUTE"}: isStreamMute=${am.isStreamMute(AudioManager.STREAM_MUSIC)} " +
            "volume=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
            "musicActive=${am.isMusicActive} took=${SystemClock.uptimeMillis() - start}ms")
    }

    private fun isChannels() = currentActivity.contains("finder", ignoreCase = true)

    private fun isActivity(pkg: String?, cls: String): Boolean {
        if (pkg == null) return false
        return runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
    }

    private fun short(cls: CharSequence?) = cls?.toString()?.substringAfterLast('.') ?: "?"

    private fun clean(s: CharSequence): String {
        val one = s.toString().replace('\n', '⏎')
        return if (one.length > MAX_TEXT) one.take(MAX_TEXT) + "…" else one
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    companion object {
        const val TAG = "WillowProbe"
        const val WECHAT = "com.tencent.mm"
        const val ACTION_DUMP = "com.willow.probe.DUMP"
        const val ACTION_SHOT = "com.willow.probe.SHOT"
        const val ACTION_MUTE = "com.willow.probe.MUTE"
        const val ACTION_UNMUTE = "com.willow.probe.UNMUTE"
        const val SETTLE_MS = 1200L
        const val MAX_DEPTH = 60
        const val MAX_TEXT = 300

        @Volatile var running = false
    }
}
