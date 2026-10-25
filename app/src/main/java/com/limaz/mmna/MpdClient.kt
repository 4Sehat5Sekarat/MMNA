package com.limaz.mmna

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class MpdException(message: String) : Exception(message)

data class MpdStatus(
    val state: String,   // play | pause | stop
    val volume: Int,     // -1 if the server has no mixer
    val elapsed: Double,
    val repeat: Boolean,
    val single: Boolean,
    val random: Boolean
)

data class MpdSong(
    val file: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Double
)

/** status and currentsong, fetched in one round trip. song is null when nothing is loaded. */
data class MpdSnapshot(val status: MpdStatus, val song: MpdSong?)

/** Quotes a string for MPD. Shared by the command and cover clients. */
fun mpdQuote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Minimal MPD client over a plain TCP socket (text protocol).
 *
 * Rules:
 * - Never call from the main thread.
 * - One instance per thread. idle() blocks its connection, so use a separate
 *   instance for idle and for commands.
 */
class MpdClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 6600,
    private val password: String = "",
    private val timeoutMs: Int = 5000
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    @Throws(MpdException::class)
    fun connect() {
        close()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            val greeting = reader?.readLine() ?: throw IOException("no greeting")
            if (!greeting.startsWith("OK MPD")) throw MpdException("not an MPD server")
            if (password.isNotEmpty()) exec("password ${mpdQuote(password)}") {}
        } catch (e: MpdException) {
            close()
            runCatching { s.close() }
            throw e
        } catch (e: Exception) {
            close()
            runCatching { s.close() }
            throw MpdException("connect failed: ${e.message}")
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    /** Sends one command. Calls onLine for every response line except OK. Throws on ACK. */
    private inline fun exec(cmd: String, onLine: (String) -> Unit) {
        val r = reader ?: throw MpdException("not connected")
        val w = writer ?: throw MpdException("not connected")
        try {
            w.write(cmd)
            w.write("\n")
            w.flush()
            while (true) {
                val line = r.readLine() ?: throw IOException("connection closed")
                if (line == "OK") return
                if (line.startsWith("ACK")) throw MpdException(line)
                onLine(line)
            }
        } catch (e: MpdException) {
            throw e
        } catch (e: Exception) {
            close()
            throw MpdException("I/O error: ${e.message}")
        }
    }

    /** status and currentsong in one command list. One round trip instead of two. */
    @Throws(MpdException::class)
    fun snapshot(): MpdSnapshot {
        val r = reader ?: throw MpdException("not connected")
        val w = writer ?: throw MpdException("not connected")
        val groups = ArrayList<Map<String, String>>(2)
        try {
            w.write("command_list_ok_begin\nstatus\ncurrentsong\ncommand_list_end\n")
            w.flush()
            var cur = HashMap<String, String>()
            while (true) {
                val line = r.readLine() ?: throw IOException("connection closed")
                when {
                    line == "list_OK" -> {
                        groups.add(cur)
                        cur = HashMap()
                    }
                    line == "OK" -> break
                    line.startsWith("ACK") -> throw MpdException(line)
                    else -> {
                        val i = line.indexOf(": ")
                        if (i > 0) cur[line.substring(0, i)] = line.substring(i + 2)
                    }
                }
            }
        } catch (e: MpdException) {
            throw e
        } catch (e: Exception) {
            close()
            throw MpdException("I/O error: ${e.message}")
        }
        if (groups.size != 2) throw MpdException("unexpected snapshot response")
        val song = groups[1]
        return MpdSnapshot(parseStatus(groups[0]), if (song.isEmpty()) null else parseSong(song))
    }

    @Throws(MpdException::class)
    fun status(): MpdStatus = parseStatus(fields("status"))

    /** Only the "file:" values of a listing. No pairs are built for the other tags. */
    @Throws(MpdException::class)
    fun listFiles(cmd: String): List<String> {
        val out = ArrayList<String>()
        exec(cmd) { line -> if (line.startsWith("file: ")) out.add(line.substring(6)) }
        return out
    }

    /** Blocks until MPD reports a change. No polling. */
    @Throws(MpdException::class)
    fun idle(): Set<String> {
        val s = socket ?: throw MpdException("not connected")
        val changed = HashSet<String>()
        s.soTimeout = 0
        try {
            exec("idle") { line -> if (line.startsWith("changed: ")) changed.add(line.substring(9)) }
        } finally {
            if (isConnected) s.soTimeout = timeoutMs
        }
        return changed
    }

    @Throws(MpdException::class)
    fun play() { exec("play") {} }

    @Throws(MpdException::class)
    fun pause(on: Boolean) { exec("pause ${if (on) 1 else 0}") {} }

    @Throws(MpdException::class)
    fun stop() { exec("stop") {} }

    @Throws(MpdException::class)
    fun next() { exec("next") {} }

    @Throws(MpdException::class)
    fun previous() { exec("previous") {} }

    @Throws(MpdException::class)
    fun seekCur(seconds: Double) {
        exec("seekcur ${String.format(Locale.US, "%.3f", seconds)}") {}
    }

    @Throws(MpdException::class)
    fun setVolume(v: Int) { exec("setvol $v") {} }

    /** name: "repeat", "single", or "random" */
    @Throws(MpdException::class)
    fun setOption(name: String, on: Boolean) { exec("$name ${if (on) 1 else 0}") {} }

    @Throws(MpdException::class)
    private fun fields(cmd: String): Map<String, String> {
        val m = HashMap<String, String>()
        exec(cmd) { line ->
            val i = line.indexOf(": ")
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 2)
        }
        return m
    }

    private fun parseStatus(m: Map<String, String>): MpdStatus = MpdStatus(
        state = m["state"] ?: "stop",
        volume = m["volume"]?.toIntOrNull() ?: -1,
        elapsed = m["elapsed"]?.toDoubleOrNull() ?: 0.0,
        repeat = m["repeat"] == "1",
        single = m["single"] == "1",
        random = m["random"] == "1"
    )

    private fun parseSong(m: Map<String, String>): MpdSong = MpdSong(
        file = m["file"],
        title = m["Title"],
        artist = m["Artist"],
        album = m["Album"],
        duration = (m["duration"] ?: m["Time"])?.toDoubleOrNull() ?: 0.0
    )
}