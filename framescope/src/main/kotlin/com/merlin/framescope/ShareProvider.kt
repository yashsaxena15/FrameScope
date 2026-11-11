package com.merlin.framescope

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal read-only provider that hands the session-summary image to the app the
 * user shares it with. (The usual FileProvider lives in AndroidX, which this app
 * does not use.) It serves exactly one file from the cache directory; access is
 * granted per share through FLAG_GRANT_READ_URI_PERMISSION.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val f = file(context!!)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> NAME
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(context!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        private const val NAME = "framescope-session.png"

        fun file(ctx: Context): File = File(File(ctx.cacheDir, "share").apply { mkdirs() }, NAME)
        fun uri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.share/$NAME")
    }
}
