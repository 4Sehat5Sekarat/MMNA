package com.limaz.mpc

import android.util.Log
import java.util.concurrent.Executors

/**
 * Fills the art cache in the background, one folder at a time.
 * Folders already cached are skipped, so calling it repeatedly is safe.
 */
class ArtPreloader(private val store: ArtStore, private val mpd: MpdConfig) {

    companion object {
        private const val TAG = "ArtPreloader"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "art-preload").apply { priority = Thread.MIN_PRIORITY }
    }

    /** files: song paths. One per folder is enough. */
    fun submit(files: List<String>) {
        executor.execute { run(files) }
    }

    /** Library mode only. Prunes folders that are gone, then fills missing covers. */
    fun submitLibrary(files: List<String>) {
        executor.execute {
            store.prune(files.map { it.substringBeforeLast('/', "") }.toSet())
            run(files)
        }
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun run(files: List<String>) {
        val seen = HashSet<String>()
        for (file in files) {
            if (Thread.interrupted()) return
            val dirKey = file.substringBeforeLast('/', "")
            if (!seen.add(dirKey)) continue
            if (store.isCached(dirKey)) continue
            try {
                store.ensure(dirKey) { MpdArt.fetch(mpd, file) }
            } catch (e: Exception) {
                // MPD dropped: stop this batch. The next run will try again.
                Log.w(TAG, "preload stopped: ${e.message}")
                return
            }
        }
    }
}