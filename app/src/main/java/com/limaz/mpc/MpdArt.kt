package com.limaz.mpc

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cover art client over one long-lived socket, separate from the command connection.
 *
 * fetch() returns:
 * - the cover bytes
 * - null if MPD has no cover for the folder, or the cover is larger than MAX_ART_BYTES
 * - throws IOException on connection or access problems. Callers must not record that as "no cover".
 *
 * Thread-safe: fetch() is synchronized, so one instance can be shared.
 */
class MpdArt(private val mpd: MpdConfig) : Closeable {

    companion object {
        private const val MAX_ART_BYTES = 8 * 1024 * 1024
        private const val BINARY_LIMIT = 256 * 1024
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var input: BufferedInputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    private val lineBuf = ByteArrayOutputStream(256)

    @Synchronized
    @Throws(IOException::class)
    fun fetch(file: String): ByteArray? {
        if (closed) throw IOException("cover client closed")
        val reused = socket != null
        return try {
            exchange(file)
        } catch (e: IOException) {
            // MPD closes idle clients after connection_timeout, so a kept socket may be dead.
            // Retry once on a new connection. A fresh connection that fails is a real error.
            if (!reused || closed) throw e
            exchange(file)
        }
    }

    override fun close() {
        closed = true
        closeSocket()
    }

    private fun exchange(file: String): ByteArray? = try {
        ensureConnected()
        readCover("albumart", file) ?: run {
            ensureConnected() // albumart drops the socket when the cover is too large
            readCover("readpicture", file)
        }
    } catch (e: Exception) {
        closeSocket()
        throw e
    }

    private fun ensureConnected() {
        if (closed) throw IOException("cover client closed")
        if (socket?.isClosed == false) return

        val s = Socket()
        try {
            s.connect(InetSocketAddress(mpd.host, mpd.port), mpd.timeoutMs)
        } catch (e: Exception) {
            s.close()
            throw e
        }
        s.soTimeout = mpd.timeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream(), 16 * 1024)
        output = s.getOutputStream()

        if (readLine()?.startsWith("OK MPD") != true) throw IOException("not an MPD server")
        if (mpd.password.isNotEmpty()) {
            send("password ${mpdQuote(mpd.password)}")
            if (readLine() != "OK") throw IOException("password rejected")
        }
        // OK, or ACK on older MPD versions without binarylimit. Either way the stream stays in sync.
        send("binarylimit $BINARY_LIMIT")
        readLine() ?: throw IOException("connection closed")
    }

    /**
     * Reads a cover with albumart or readpicture, chunk by chunk.
     * Returns null when MPD has no cover of this kind, or when the cover is too large.
     */
    private fun readCover(cmd: String, file: String): ByteArray? {
        var target: ByteArray? = null
        var total = -1
        var offset = 0
        do {
            send("$cmd ${mpdQuote(file)} $offset")
            var chunk: ByteArray? = null
            while (true) {
                val line = readLine() ?: throw IOException("connection closed")
                when {
                    line.startsWith("ACK") -> {
                        // 3 = password, 4 = permission. This is an access problem, not "no cover".
                        val code = ackCode(line)
                        if (code == 3 || code == 4) throw IOException("access denied: $line")
                        return null
                    }
                    line.startsWith("size: ") && total < 0 -> {
                        total = line.substring(6).toInt()
                        if (total > MAX_ART_BYTES) {
                            // Binary data is still pending, so this connection cannot be reused
                            closeSocket()
                            return null
                        }
                        target = ByteArray(total)
                    }
                    line.startsWith("binary: ") -> {
                        chunk = readFully(line.substring(8).toInt())
                        input?.read() // newline after the binary data
                    }
                    line == "OK" -> break
                }
            }
            val buf = target ?: throw IOException("no size from MPD")
            val part = chunk ?: ByteArray(0)
            if (part.isEmpty()) break
            if (offset + part.size > total) throw IOException("cover larger than announced")
            System.arraycopy(part, 0, buf, offset, part.size)
            offset += part.size
        } while (offset < total)

        val buf = target ?: throw IOException("no size from MPD")
        if (offset != total) throw IOException("incomplete cover data")
        return buf
    }

    private fun send(cmd: String) {
        val out = output ?: throw IOException("not connected")
        out.write("$cmd\n".toByteArray())
        out.flush()
    }

    /** One response line without its newline. Null at end of stream. */
    private fun readLine(): String? {
        val inp = input ?: throw IOException("not connected")
        lineBuf.reset()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (lineBuf.size() == 0) null else String(lineBuf.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) return String(lineBuf.toByteArray(), Charsets.UTF_8)
            lineBuf.write(b)
        }
    }

    private fun readFully(n: Int): ByteArray {
        if (n < 0 || n > MAX_ART_BYTES) throw IOException("bad binary size: $n")
        val inp = input ?: throw IOException("not connected")
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = inp.read(buf, read, n - read)
            if (r == -1) throw IOException("EOF while reading binary data")
            read += r
        }
        return buf
    }

    /** "ACK [50@0] {albumart} ..." gives 50. Returns -1 if the format is unexpected. */
    private fun ackCode(line: String): Int {
        val at = line.indexOf('@')
        if (!line.startsWith("ACK [") || at <= 5) return -1
        return line.substring(5, at).toIntOrNull() ?: -1
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}