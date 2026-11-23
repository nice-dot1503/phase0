package com.primegametranslator.phase0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object CaptureState {
    @Volatile var status = "Idle"
    @Volatile var resolution = "-"
    @Volatile var fps = 0.0
    @Volatile var ocrStatus = "-"
    @Volatile var ocrMs = -1L
    @Volatile var text = ""
    @Volatile var error = ""
    @Volatile var black = false
    @Volatile var brightness = 0.0
    @Volatile var measured = 0L
    @Volatile var sawBright = false
    @Volatile var sawBlack = false
    @Volatile var ocrRuns = 0
    @Volatile var ocrHits = 0
    @Volatile var appVisible = false

    fun reset() {
        status = "Idle"; resolution = "-"; fps = 0.0; ocrStatus = "-"; ocrMs = -1L
        text = ""; error = ""; black = false; brightness = 0.0; measured = 0L
        sawBright = false; sawBlack = false; ocrRuns = 0; ocrHits = 0
    }
}

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.primegametranslator.phase0.START"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "phase0"
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var stopped = false
    private var frameCount = 0L
    private var lastCount = 0L
    private var lastProc = 0L
    private var darkSince = 0L
    @Volatile private var ocrBusy = false

    private val cb = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopped) CaptureState.error = "MediaProjection ถูกหยุดโดยระบบหรือผู้ใช้"
            cleanup()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = frameCount
            CaptureState.fps = (f - lastCount).toDouble()
            lastCount = f
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("phase0-capture").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) startCapture(intent)
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Phase0", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setContentTitle("Phase0")
            .setContentText("กำลังจับภาพหน้าจอเพื่อทดสอบ")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
        if (projection != null || stopped) return
        try {
            CaptureState.reset()
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) throw IllegalStateException("ไม่ได้รับข้อมูลสิทธิ์จับภาพ")

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
                ?: throw IllegalStateException("getMediaProjection ได้ค่า null")
            projection = mp
            mp.registerCallback(cb, handler)

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val w = maxOf(dm.widthPixels, dm.heightPixels)
            val hh = minOf(dm.widthPixels, dm.heightPixels)

            val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)
            vd = mp.createVirtualDisplay(
                "phase0", w, hh, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
            CaptureState.resolution = "${w}x${hh}"
            CaptureState.status = "Running"
            handler.postDelayed(tick, 1000)
        } catch (e: Exception) {
            CaptureState.status = "Error"
            CaptureState.error = e.toString()
            cleanup()
        }
    }

    private fun onImage(r: ImageReader) {
        if (stopped) return
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (CaptureState.appVisible) {
            darkSince = 0L
            img.close()
            return
        }
        if (now - lastProc < 700) {
            img.close()
            return
        }
        lastProc = now
        val bmp = try {
            toBitmap(img)
        } catch (e: Exception) {
            CaptureState.error = "แปลงภาพ: $e"
            null
        } finally {
            img.close()
        }
        if (bmp == null) return

        val avg = avgBrightness(bmp)
        CaptureState.brightness = avg
        CaptureState.measured++
        if (avg < 8.0) {
            if (darkSince == 0L) darkSince = now
            if (now - darkSince > 3000) {
                CaptureState.black = true
                CaptureState.sawBlack = true
            }
        } else {
            darkSince = 0L
            CaptureState.black = false
            CaptureState.sawBright = true
        }

        if (ocrBusy) {
            bmp.recycle()
            return
        }
        ocrBusy = true
        val t0 = SystemClock.elapsedRealtime()
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { res ->
                    val t = res.text.trim()
                    CaptureState.ocrMs = SystemClock.elapsedRealtime() - t0
                    CaptureState.ocrRuns++
                    CaptureState.text = t
                    if (t.isNotEmpty()) {
                        CaptureState.ocrHits++
                        CaptureState.ocrStatus = "พบข้อความ"
                    } else {
                        CaptureState.ocrStatus = "ไม่พบข้อความ"
                    }
                }
                .addOnFailureListener { e ->
                    CaptureState.ocrStatus = "ผิดพลาด"
                    CaptureState.error = "OCR: ${e.message}"
                }
                .addOnCompleteListener {
                    bmp.recycle()
                    ocrBusy = false
                }
        } catch (e: Exception) {
            bmp.recycle()
            ocrBusy = false
            CaptureState.error = "OCR: $e"
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val bw = img.width + pad / p.pixelStride
        val full = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        if (bw == img.width) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        full.recycle()
        return cropped
    }

    private fun avgBrightness(b: Bitmap): Double {
        val sx = 20
        val sy = 12
        var sum = 0L
        for (i in 0 until sy) {
            for (j in 0 until sx) {
                val p = b.getPixel((b.width - 1) * j / (sx - 1), (b.height - 1) * i / (sy - 1))
                sum += Color.red(p) + Color.green(p) + Color.blue(p)
            }
        }
        return sum.toDouble() / (sx * sy * 3)
    }

    private fun cleanup() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try {
            projection?.unregisterCallback(cb)
            projection?.stop()
        } catch (_: Exception) {}
        if (CaptureState.status == "Running") CaptureState.status = "Idle"
        CaptureState.fps = 0.0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        thread.quitSafely()
        recognizer.close()
        super.onDestroy()
    }
}
