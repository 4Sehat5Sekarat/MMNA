package com.limaz.mpc

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
    val state: String,      // play | pause | stop
    val volume: Int,        // -1 if the server has no mixer
    val songPos: Int?,
    val elapsed: Double,
    val duration: Double,
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
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))

            val greeting = reader!!.readLine() ?: throw IOException("no greeting")
            if (!greeting.startsWith("OK MPD")) throw MpdException("not an MPD server")

            if (password.isNotEmpty()) send("password ${quote(password)}")
        } catch (e: MpdException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw MpdException("connect failed: ${e.message}")
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Closing on purpose, errors can be ignored
        }
        socket = null
        reader = null
        writer = null
    }

    /** Sends one command and returns the key/value pairs of the response. */
    @Throws(MpdException::class)
    fun send(cmd: String): List<Pair<String, String>> {
        val r = reader ?: throw MpdException("not connected")
        val w = writer ?: throw MpdException("not connected")
        try {
            w.write(cmd)
            w.write("\n")
            w.flush()

            val out = ArrayList<Pair<String, String>>()
            while (true) {
                val line = r.readLine() ?: throw IOException("connection closed")
                when {
                    line == "OK" -> return out
                    line.startsWith("ACK") -> throw MpdException(line)
                    else -> {
                        val i = line.indexOf(": ")
                        if (i > 0) out.add(line.substring(0, i) to line.substring(i + 2))
                    }
                }
            }
        } catch (e: MpdException) {
            throw e
        } catch (e: Exception) {
            close()
            throw MpdException("I/O error: ${e.message}")
        }
    }

    /** Blocks until MPD reports a change. No polling. */
    @Throws(MpdException::class)
    fun idle(): Set<String> {
        val s = socket ?: throw MpdException("not connected")
        s.soTimeout = 0
        try {
            return send("idle").filter { it.first == "changed" }.map { it.second }.toSet()
        } finally {
            if (isConnected) s.soTimeout = timeoutMs
        }
    }

    @Throws(MpdException::class)
    fun play() { send("play") }

    @Throws(MpdException::class)
    fun pause(on: Boolean) { send("pause ${if (on) 1 else 0}") }

    @Throws(MpdException::class)
    fun stop() { send("stop") }

    @Throws(MpdException::class)
    fun next() { send("next") }

    @Throws(MpdException::class)
    fun previous() { send("previous") }

    @Throws(MpdException::class)
    fun seekCur(seconds: Double) {
        send("seekcur ${String.format(Locale.US, "%.3f", seconds)}")
    }

    @Throws(MpdException::class)
    fun setVolume(v: Int) {
        send("setvol $v")
    }

    /** name: "repeat", "single", or "random" */
    @Throws(MpdException::class)
    fun setOption(name: String, on: Boolean) {
        send("$name ${if (on) 1 else 0}")
    }

    @Throws(MpdException::class)
    fun status(): MpdStatus {
        val m = send("status").toMap()
        return MpdStatus(
            state = m["state"] ?: "stop",
            volume = m["volume"]?.toIntOrNull() ?: -1,
            songPos = m["song"]?.toIntOrNull(),
            elapsed = m["elapsed"]?.toDoubleOrNull() ?: 0.0,
            duration = m["duration"]?.toDoubleOrNull() ?: 0.0,
            repeat = m["repeat"] == "1",
            single = m["single"] == "1",
            random = m["random"] == "1"
        )
    }

    @Throws(MpdException::class)
    fun currentSong(): MpdSong? {
        val m = send("currentsong").toMap()
        if (m.isEmpty()) return null
        return MpdSong(
            file = m["file"],
            title = m["Title"],
            artist = m["Artist"],
            album = m["Album"],
            duration = (m["duration"] ?: m["Time"])?.toDoubleOrNull() ?: 0.0
        )
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}