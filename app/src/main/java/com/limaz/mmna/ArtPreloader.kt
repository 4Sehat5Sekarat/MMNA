package com.limaz.mmna

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Fills the art cache in the background, one folder at a time.
 * Folders already cached are skipped, so calling it repeatedly is safe.
 *
 * Uses the shared MpdArt instance, so it never opens its own socket.
 */
class ArtPreloader(private val store: ArtStore, private val cover: MpdArt) {

    companion object {
        private const val TAG = "ArtPreloader"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "art-preload").apply { priority = Thread.MIN_PRIORITY }
    }

    /** Queue mode. Files are song paths. One per folder is enough, duplicates are dropped. */
    fun submit(files: List<String>) {
        enqueue { run(folderSamples(files)) }
    }

    /** Library mode. Prunes folders that are gone, then fills missing covers. */
    fun submitLibrary(files: List<String>) {
        enqueue {
            store.prune(files.map { it.substringBeforeLast('/', "") }.toSet())
            run(folderSamples(files))
        }
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun enqueue(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Preloader already stopped, nothing to do
        }
    }

    /** One representative file per folder, in the order MPD returned them. */
    private fun folderSamples(files: List<String>): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (file in files) {
            val dirKey = file.substringBeforeLast('/', "")
            if (seen.add(dirKey)) out.add(dirKey to file)
        }
        return out
    }

    private fun run(folders: List<Pair<String, String>>) {
        for ((dirKey, file) in folders) {
            if (Thread.interrupted()) return
            if (store.isCached(dirKey)) continue
            try {
                store.ensure(dirKey, file, cover)
            } catch (e: Exception) {
                // MPD dropped or refused: stop this batch. The next trigger will try again.
                Log.w(TAG, "preload stopped: ${e.message}")
                return
            }
        }
    }
}