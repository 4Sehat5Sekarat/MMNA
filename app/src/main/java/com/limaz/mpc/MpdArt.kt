package com.limaz.mpc

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Fetches cover art over its own socket, so the main command connection is not affected. */
object MpdArt {

    private const val MAX_ART_BYTES = 8 * 1024 * 1024

    /** Null = no cover exists. Exception = connection problem, do not record the result. */
    @Throws(Exception::class)
    fun fetch(mpd: MpdConfig, file: String): ByteArray? =
        readArt(mpd, "albumart", file) ?: readArt(mpd, "readpicture", file)

    private fun readArt(mpd: MpdConfig, cmd: String, file: String): ByteArray? {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(mpd.host, mpd.port), mpd.timeoutMs)
            s.soTimeout = mpd.timeoutMs
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()

            readTextLine(input) ?: throw IOException("no greeting")
            if (mpd.password.isNotEmpty()) {
                out.write("password ${mpdQuote(mpd.password)}\n".toByteArray())
                out.flush()
                if (readTextLine(input) != "OK") throw IOException("password rejected")
            }

            val result = ByteArrayOutputStream()
            var offset = 0
            var total = -1
            while (true) {
                out.write("$cmd ${mpdQuote(file)} $offset\n".toByteArray())
                out.flush()

                var size = -1
                var chunk: ByteArray? = null
                while (true) {
                    val line = readTextLine(input) ?: throw IOException("connection closed while fetching cover")
                    when {
                        // ACK means this command has no cover. It is not a connection error.
                        line.startsWith("ACK") -> return null
                        line.startsWith("size: ") -> size = line.substring(6).toInt()
                        line.startsWith("binary: ") -> {
                            chunk = readFully(input, line.substring(8).toInt())
                            input.read() // newline after the binary data
                        }
                        line == "OK" -> break
                    }
                }

                val c = chunk ?: return null
                if (total < 0) total = size
                if (total < 0 || total > MAX_ART_BYTES) return null
                result.write(c)
                offset += c.size
                if (c.isEmpty() || offset >= total) break
            }
            return result.toByteArray()
        } finally {
            s.close()
        }
    }

    private fun readTextLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            buf.write(b)
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) throw IOException("EOF while reading binary data")
            read += r
        }
        return buf
    }

    private fun mpdQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}