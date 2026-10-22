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
import java.util.concurrent.ConcurrentHashMap

/**
 * Album art cache.
 * - Table dir_art maps a folder to a thumbnail hash. An empty hash means the folder has no art.
 * - Thumbnail files are named after their content hash, so identical covers are stored once.
 * - An in-memory map holds the answer per folder, so repeated lookups skip SQLite and the disk.
 *
 * Thread-safe. Lookups can run from any thread, writes are serialized by SQLite.
 */
class ArtStore(context: Context, private val maxSize: Int) :
    SQLiteOpenHelper(context, "art.db", null, 1) {

    private val dir = File(context.filesDir, "art").apply { mkdirs() }

    /** dirKey -> hash ("" = no cover). Filled from the database on first use. */
    private val known = ConcurrentHashMap<String, String>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE dir_art (dir TEXT PRIMARY KEY, hash TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** True if the folder is recorded and its thumbnail file still exists (or it has no cover). */
    fun isCached(dirKey: String): Boolean {
        val hash = hashFor(dirKey) ?: return false
        return hash.isEmpty() || File(dir, "$hash.jpg").exists()
    }

    /**
     * Makes sure the folder is recorded. Fetches the cover only if it is not cached.
     * Throws if MPD cannot be reached. In that case nothing is recorded, so the next call retries.
     */
    @Throws(Exception::class)
    fun ensure(dirKey: String, file: String, cover: MpdArt) {
        if (isCached(dirKey)) return
        val raw = cover.fetch(file)
        val hash = raw?.let { saveThumb(it) } ?: ""
        remember(dirKey, hash)
    }

    /** Bitmap for the folder, or null if it has no cover. Fetches through [cover] when needed. */
    @Throws(Exception::class)
    fun thumbFor(dirKey: String, file: String, cover: MpdArt): Bitmap? {
        ensure(dirKey, file, cover)
        val hash = hashFor(dirKey) ?: return null
        if (hash.isEmpty()) return null
        val bmp = BitmapFactory.decodeFile(File(dir, "$hash.jpg").path)
        if (bmp == null) {
            // Broken or missing thumbnail. Forget the folder so the next call fetches it again.
            forget(dirKey)
        }
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

    /** Memory first, then the database. Null means the folder has never been recorded. */
    private fun hashFor(dirKey: String): String? {
        known[dirKey]?.let { return it }
        val hash = readableDatabase.query(
            "dir_art", arrayOf("hash"), "dir=?", arrayOf(dirKey), null, null, null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        known[dirKey] = hash
        return hash
    }

    private fun remember(dirKey: String, hash: String) {
        writableDatabase.insertWithOnConflict(
            "dir_art", null,
            ContentValues().apply { put("dir", dirKey); put("hash", hash) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        known[dirKey] = hash
    }

    private fun forget(dirKey: String) {
        writableDatabase.delete("dir_art", "dir=?", arrayOf(dirKey))
        known.remove(dirKey)
    }

    /**
     * Removes entries for folders that no longer exist, and thumbnails nothing refers to.
     * Library mode only: validDirs must be the full library, or valid folders get removed.
     */
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
        stale.forEach {
            db.delete("dir_art", "dir=?", arrayOf(it))
            known.remove(it)
        }

        dir.listFiles()?.forEach { f ->
            if (f.nameWithoutExtension !in used) f.delete()
        }
        // The memory map may still point to removed thumbnails, so drop it. It refills from the database.
        known.clear()
    }
}