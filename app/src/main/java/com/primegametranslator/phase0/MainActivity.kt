package com.primegametranslator.phase0

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tvSummary: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRes: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvOcr: TextView
    private lateinit var tvMs: TextView
    private lateinit var tvText: TextView
    private lateinit var tvError: TextView
    private lateinit var tvBlack: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            h.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvSummary = findViewById(R.id.tvSummary)
        tvStatus = findViewById(R.id.tvStatus)
        tvRes = findViewById(R.id.tvRes)
        tvFps = findViewById(R.id.tvFps)
        tvOcr = findViewById(R.id.tvOcr)
        tvMs = findViewById(R.id.tvMs)
        tvText = findViewById(R.id.tvText)
        tvError = findViewById(R.id.tvError)
        tvBlack = findViewById(R.id.tvBlack)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
        }
        findViewById<Button>(R.id.btnSummary).setOnClickListener {
            tvSummary.text = summary()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        CaptureState.appVisible = true
        h.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        CaptureState.appVisible = false
        h.removeCallbacks(refresh)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
        } else {
            CaptureState.status = "Error"
            CaptureState.error = "ผู้ใช้ไม่อนุญาตการจับภาพหน้าจอ"
        }
    }

    private fun render() {
        val s = CaptureState
        var st = s.status
        if (st == "Running" && s.appVisible) st += " (แอปนี้อยู่หน้าจอ: หยุดวัดชั่วคราว)"
        tvStatus.text = getString(R.string.l_status, st)
        tvRes.text = getString(R.string.l_res, s.resolution)
        tvFps.text = getString(R.string.l_fps, "%.1f".format(s.fps))
        tvOcr.text = getString(R.string.l_ocr, s.ocrStatus)
        tvMs.text = getString(R.string.l_ms, if (s.ocrMs < 0) "-" else "${s.ocrMs} ms")
        tvText.text = getString(R.string.l_text, s.text.take(600))
        tvError.text = getString(R.string.l_err, if (s.error.isEmpty()) "ไม่มี" else s.error)
        val b = when {
            s.measured == 0L -> "-"
            s.black -> "จอดำ (สว่างเฉลี่ย %.0f/255)".format(s.brightness)
            else -> "ปกติ (สว่างเฉลี่ย %.0f/255)".format(s.brightness)
        }
        tvBlack.text = getString(R.string.l_black, b)
    }

    private fun summary(): String {
        val s = CaptureState
        val stat = "\n(วัด ${s.measured} เฟรม, OCR ${s.ocrRuns} ครั้ง, พบข้อความ ${s.ocrHits} ครั้ง)"
        return when {
            s.measured == 0L ->
                "ยังสรุปไม่ได้: ยังไม่มีเฟรมที่วัดจากแอปอื่น (กด Start แล้วสลับไปแอปที่จะทดสอบ รอสักครู่ แล้วกลับมากด)" +
                    if (s.error.isNotEmpty()) "\nError: ${s.error}" else ""
            !s.sawBright && s.sawBlack -> "FAIL: ภาพดำตลอด$stat"
            s.sawBright && !s.sawBlack && s.ocrHits > 0 -> "PASS: ภาพไม่ดำและ OCR อ่านข้อความได้$stat"
            s.sawBright && s.sawBlack -> "PARTIAL: จับได้แต่เคยมีช่วงภาพดำเกิน 3 วินาที$stat"
            s.sawBright -> "PARTIAL: ภาพไม่ดำ แต่ OCR ยังไม่พบข้อความอังกฤษ$stat"
            else -> "PARTIAL: ภาพมืดแต่ยังไม่ครบ 3 วินาที ลองทดสอบนานขึ้น$stat"
        }
    }

    companion object {
        private const val REQ = 100
    }
}
