package com.willow

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.roundToInt

/**
 * Reads WeChat's screen as text: screenshot → on-phone Chinese OCR (Google ML Kit, model inside
 * the APK, works offline). The picture never leaves the phone and is thrown away right after OCR.
 *
 * On Android 14+ it photographs only WeChat's own window, so notifications, the status bar and
 * Willow's own banners are never in the picture. Older phones photograph the whole screen and
 * drop any text that lies on a window that isn't WeChat.
 */
class ScreenReader(private val svc: AccessibilityService, private val log: (String) -> Unit) {

    /** One read of the screen. Line coordinates are in [width] × [height] image pixels. */
    class Shot(
        val lines: List<OcrLine>,
        val width: Int,
        val height: Int,
        private val scale: Float,
        private val offsetX: Int,
        private val offsetY: Int,
        val ms: Long,
        val whole: Boolean,
    ) {
        /** Where a line's centre is on the real screen, for tapping it. */
        fun screenX(l: OcrLine) = l.cx / scale + offsetX
        fun screenY(l: OcrLine) = l.cy / scale + offsetY
        fun screenX(fraction: Float) = width * fraction / scale + offsetX
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var windowShotFailed = false

    val reading get() = busy

    /**
     * Reads the screen and calls [done] on the main thread (null if it couldn't).
     * [whole] = the whole screen including WeChat's popups (the long-press menu, a product page);
     * otherwise only WeChat's main window. Returns false if a read is already running.
     */
    fun read(whole: Boolean, done: (Shot?) -> Unit): Boolean {
        if (busy) return false
        busy = true
        val started = SystemClock.uptimeMillis()
        val finish = { shot: Shot? -> busy = false; done(shot) }

        val win = if (!whole && Build.VERSION.SDK_INT >= 34 && !windowShotFailed) wechatMainWindow() else null
        if (win != null) {
            val b = Rect().also { win.getBoundsInScreen(it) }
            shootWindow(win.id, b, started, finish)
        } else {
            shootDisplay(whole, started, finish, retries = 3)
        }
        return true
    }

    fun close() = recognizer.close()

    private fun shootWindow(id: Int, bounds: Rect, started: Long, finish: (Shot?) -> Unit) {
        if (Build.VERSION.SDK_INT < 34) return
        svc.takeScreenshotOfWindow(id, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) =
                recognize(r, bounds.left, bounds.top, emptyList(), started, whole = false, finish)

            override fun onFailure(code: Int) {
                // Not supported for this window (some phones): use whole-screen shots from now on.
                log("window screenshot failed ($code), using the whole screen")
                windowShotFailed = true
                shootDisplay(false, started, finish, retries = 3)
            }
        })
    }

    private fun shootDisplay(whole: Boolean, started: Long, finish: (Shot?) -> Unit, retries: Int) {
        val hidden = notWeChatAreas()
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) =
                recognize(r, 0, 0, hidden, started, whole, finish)

            override fun onFailure(code: Int) {
                // Android allows about 3 screenshots a second; wait and try again.
                if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retries > 0) {
                    main.postDelayed({ shootDisplay(whole, started, finish, retries - 1) }, 400)
                } else {
                    log("screenshot failed ($code)")
                    finish(null)
                }
            }
        })
    }

    private fun recognize(
        r: AccessibilityService.ScreenshotResult, offsetX: Int, offsetY: Int, hidden: List<Rect>,
        started: Long, whole: Boolean, finish: (Shot?) -> Unit,
    ) {
        val buffer = r.hardwareBuffer
        val image: Bitmap
        val scale: Float
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, r.colorSpace) ?: return finish(null)
            val full = hw.copy(Bitmap.Config.ARGB_8888, false) // OCR needs pixels in normal memory
            hw.recycle()
            scale = minOf(1f, MAX_WIDTH.toFloat() / full.width)
            image = if (scale < 1f) {
                Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true)
                    .also { full.recycle() }
            } else full
        } catch (e: RuntimeException) {
            log("screenshot unreadable: ${e.message}")
            return finish(null)
        } finally {
            buffer.close()
        }

        recognizer.process(InputImage.fromBitmap(image, 0))
            .addOnSuccessListener(svc.mainExecutor) { text ->
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    val box = l.boundingBox ?: return@mapNotNull null
                    // Whole-screen shots: skip text on notifications, the status bar, Willow's banners.
                    val sx = (box.centerX() / scale).toInt() + offsetX
                    val sy = (box.centerY() / scale).toInt() + offsetY
                    if (hidden.any { it.contains(sx, sy) }) return@mapNotNull null
                    OcrLine(l.text, box.left, box.top, box.right, box.bottom)
                }
                val shot = Shot(lines, image.width, image.height, scale, offsetX, offsetY,
                    SystemClock.uptimeMillis() - started, whole)
                image.recycle()
                finish(shot)
            }
            .addOnFailureListener(svc.mainExecutor) { e ->
                log("OCR failed: ${e.message}")
                image.recycle()
                finish(null)
            }
    }

    /** WeChat's full-screen activity window (not its popups). */
    private fun wechatMainWindow(): AccessibilityWindowInfo? =
        svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == WillowService.WECHAT }
            .maxByOrNull { w -> Rect().also { w.getBoundsInScreen(it) }.let { it.width() * it.height() } }

    /** Screen areas covered by anything that isn't WeChat: status bar, notifications, keyboard, Willow. */
    private fun notWeChatAreas(): List<Rect> =
        svc.windows.filterNot { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == WillowService.WECHAT }
            .map { w -> Rect().also { w.getBoundsInScreen(it) } }

    private companion object {
        /** Big enough for small captions (ML Kit wants ~24 px per Chinese character), small enough to be quick. */
        const val MAX_WIDTH = 1080
    }
}
