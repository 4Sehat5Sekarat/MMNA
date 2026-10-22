package com.limaz.mpc

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Album art cache.
 * - Table dir_art maps a folder to a thumbnail hash. An empty hash means the folder has no art.
 * - Thumbnail files are named after their content hash, so identical covers are stored once.
 */
class ArtStore(context: Context, private val maxSize: Int) :
    SQLiteOpenHelper(context, "art.db", null, 1) {

    private val dir = File(context.filesDir, "art").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE dir_art (dir TEXT PRIMARY KEY, hash TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** True if the folder is recorded and its thumbnail file still exists. */
    fun isCached(dirKey: String): Boolean {
        val hash = lookup(dirKey) ?: return false
        return hash.isEmpty() || File(dir, "$hash.jpg").exists()
    }

    /**
     * Fetches and stores the cover if it is not cached yet.
     * fetch may throw (for example if MPD dropped). In that case nothing is recorded.
     */
    fun ensure(dirKey: String, fetch: () -> ByteArray?) {
        if (isCached(dirKey)) return
        val raw = fetch()
        val hash = raw?.let { saveThumb(it) } ?: ""
        remember(dirKey, hash)
    }

    /** Bitmap for the folder, or null if it has no cover. */
    fun thumbFor(dirKey: String, fetch: () -> ByteArray?): Bitmap? {
        ensure(dirKey, fetch)
        val hash = lookup(dirKey) ?: return null
        if (hash.isEmpty()) return null
        val bmp = BitmapFactory.decodeFile(File(dir, "$hash.jpg").path)
        if (bmp == null) forget(dirKey) // broken or missing file, it will be fetched again
        return bmp
    }

    /** Scales down, compresses to JPEG, stores under the hash. Returns the hash, or null on failure. */
    private fun saveThumb(raw: ByteArray): String? {
        val bmp = decodeScaled(raw) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        val hash = sha256(bytes)
        val file = File(dir, "$hash.jpg")
        if (!file.exists()) file.writeBytes(bytes)
        return hash
    }

    private fun decodeScaled(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize &&
            bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun lookup(dirKey: String): String? {
        readableDatabase.query("dir_art", arrayOf("hash"), "dir=?", arrayOf(dirKey), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun remember(dirKey: String, hash: String) {
        writableDatabase.insertWithOnConflict(
            "dir_art", null,
            ContentValues().apply { put("dir", dirKey); put("hash", hash) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun forget(dirKey: String) {
        writableDatabase.delete("dir_art", "dir=?", arrayOf(dirKey))
    }

    /** Removes entries for folders that no longer exist, and thumbnails nothing refers to. */
    fun prune(validDirs: Set<String>) {
        val db = writableDatabase
        val stale = mutableListOf<String>()
        val used = HashSet<String>()

        db.query("dir_art", arrayOf("dir", "hash"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) !in validDirs) stale += c.getString(0)
                else used += c.getString(1)
            }
        }
        stale.forEach { db.delete("dir_art", "dir=?", arrayOf(it)) }

        dir.listFiles()?.forEach { f ->
            if (f.nameWithoutExtension !in used) f.delete()
        }
    }
}