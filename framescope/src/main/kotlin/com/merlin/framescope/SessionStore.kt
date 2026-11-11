package com.merlin.framescope

import android.content.Context
import com.merlin.fpscore.SessionSummary
import java.io.File

/** Keeps the most recent session summary in the app's private storage. */
internal object SessionStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_session.txt")

    fun save(ctx: Context, s: SessionSummary) {
        try { file(ctx).writeText(s.encode()) } catch (_: Throwable) {}
    }

    fun load(ctx: Context): SessionSummary? = try {
        val f = file(ctx)
        if (f.exists()) SessionSummary.decode(f.readText()) else null
    } catch (_: Throwable) {
        null
    }
}
