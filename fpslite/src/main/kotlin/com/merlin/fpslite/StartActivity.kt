package com.merlin.fpslite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Invisible trampoline used by the Quick Settings tile to start the overlay. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shizuku state is handled by the overlay itself (it shows what is missing).
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
