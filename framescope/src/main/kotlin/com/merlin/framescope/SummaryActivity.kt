package com.merlin.framescope

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.merlin.fpscore.SessionSummary
import java.io.FileOutputStream

/** Shows the last session's report and lets the user share it as an image. */
class SummaryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(OverlayService.NOTIF_SUMMARY) } catch (_: Throwable) {}

        val root = ui.column(16)
        val s = SessionStore.load(this)
        if (s == null) {
            root.addView(ui.text("No session yet", 20f, bold = true))
            root.addView(ui.text("Start the overlay and play for at least 10 seconds. A summary appears here when the overlay stops.", 14f, ui.muted), ui.lp(6))
        } else {
            val label = Ui.appLabel(this, s.pkg)
            root.addView(SummaryView(this, s, label),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(ui.button("Share as image").apply { setOnClickListener { share(s, label) } }, ui.lp(14))
            root.addView(ui.text("Stutters are frames that took more than twice the previous ones. Throttled is the share of time the CPU's maximum speed was limited (n/a when the phone doesn't expose it).", 12f, ui.muted), ui.lp(12))
        }
        root.addView(ui.outlineButton("Close").apply { setOnClickListener { finish() } }, ui.lp(12))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(root)
        })
    }

    private fun share(s: SessionSummary, label: String) {
        try {
            val w = 1080
            val bmp = Bitmap.createBitmap(w, (w * SummaryView.RATIO).toInt(), Bitmap.Config.ARGB_8888)
            SummaryView.render(Canvas(bmp), w.toFloat(), s, label)
            FileOutputStream(ShareProvider.file(this)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val uri = ShareProvider.uri(this)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "$label: avg ${Math.round(s.avgFps)} FPS, 1% low ${Math.round(s.low1Fps)} (FrameScope)")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("FrameScope session", uri)
            startActivity(Intent.createChooser(send, "Share session summary"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't create the image: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
