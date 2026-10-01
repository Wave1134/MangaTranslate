package com.example.mangatranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var resultView: ResultView? = null
    private var want = false
    private val handler = Handler(Looper.getMainLooper())

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
    )

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("t", "Translate", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "t")
            .setContentTitle("Manhwa Translate กำลังทำงาน")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra("data")!!)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        setupCapture()
        translator.downloadModelIfNeeded()
        showButton()
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val b = wm.maximumWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay(
            "cap", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (want) {
                want = false
                val bmp = toBitmap(img)
                img.close()
                process(bmp)
            } else img.close()
        }, handler)
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun onTap() {
        resultView?.let { wm.removeView(it) }
        resultView = null
        button?.visibility = View.GONE
        handler.postDelayed({ want = true }, 250)
        handler.postDelayed({
            if (want) { want = false; button?.visibility = View.VISIBLE; toast("จับภาพไม่สำเร็จ ลองใหม่") }
        }, 3000)
    }

    private fun process(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val blocks = t.textBlocks.filter { it.boundingBox != null && it.text.trim().length > 1 }
                if (blocks.isEmpty()) {
                    button?.visibility = View.VISIBLE
                    toast("ไม่พบข้อความในภาพ")
                    return@addOnSuccessListener
                }
                val items = mutableListOf<Pair<Rect, String>>()
                var left = blocks.size
                blocks.forEach { blk ->
                    translator.translate(blk.text.replace("\n", " ")).addOnCompleteListener { task ->
                        items.add(blk.boundingBox!! to (if (task.isSuccessful) task.result else blk.text))
                        if (--left == 0) show(items)
                    }
                }
            }
            .addOnFailureListener {
                button?.visibility = View.VISIBLE
                toast("OCR ล้มเหลว")
            }
    }

    private fun show(items: List<Pair<Rect, String>>) {
        button?.visibility = View.VISIBLE
        val v = ResultView(this, items)
        v.setOnClickListener {
            wm.removeView(v); resultView = null
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
        resultView = v
    }

    private fun showButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "แปล"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDDE91E63.toInt()) }
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        button?.let { wm.removeView(it) }
        resultView?.let { wm.removeView(it) }
        reader?.close(); projection?.stop()
        recognizer.close(); translator.close()
        super.onDestroy()
    }
}

class ResultView(c: Context, private val items: List<Pair<Rect, String>>) : View(c) {
    private val bg = Paint().apply { color = 0xF5FFFFFF.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    override fun onDraw(c: Canvas) {
        val pad = 6f
        for ((r, t) in items) {
            c.drawRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, bg)
            var size = 44f
            var sl: StaticLayout
            do {
                tp.textSize = size
                sl = StaticLayout.Builder.obtain(t, 0, t.length, tp, r.width().coerceAtLeast(40))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                size -= 2f
            } while (sl.height > r.height() && size > 18f)
            c.save()
            c.translate(r.left.toFloat(), r.top + (r.height() - sl.height).coerceAtLeast(0) / 2f)
            sl.draw(c)
            c.restore()
        }
    }
}
