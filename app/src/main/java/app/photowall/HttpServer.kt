package app.photowall

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long,
)

class Response(
    val status: Int,
    val contentType: String = "application/json; charset=utf-8",
    val bytes: ByteArray? = null,
    val file: File? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    fun withHeaders(extra: Map<String, String>) = Response(status, contentType, bytes, file, headers + extra)

    companion object {
        fun json(obj: JSONObject, status: Int = 200) = Response(status, bytes = obj.toString().toByteArray())
        fun error(status: Int, message: String) =
            Response(status, bytes = JSONObject().put("error", message).toString().toByteArray())
    }
}

/** A tiny blocking HTTP/1.1 server: enough for a web UI and streaming uploads, with no dependencies. */
class HttpServer(private val handler: (Request) -> Response) {
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "photowall-http").apply { isDaemon = true }
    }

    @Volatile
    var port = 0
        private set

    /** Binds the first free port in [preferred]..[preferred]+10. */
    fun start(preferred: Int): Boolean {
        for (p in preferred..preferred + 10) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                server = s
                port = p
                break
            } catch (e: IOException) {
                // try the next port
            }
        }
        val s = server ?: return false
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { serve(c) }
                } catch (e: IOException) {
                    if (s.isClosed) break
                }
            }
        }
        return true
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
        }
        pool.shutdownNow()
    }

    private fun serve(sock: Socket) {
        sock.use {
            try {
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
                val out = BufferedOutputStream(sock.getOutputStream(), 32 * 1024)

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
                }

                val target = parts[1]
                val qi = target.indexOf('?')
                val rawPath = if (qi >= 0) target.substring(0, qi) else target
                val path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
                val query = parseQuery(if (qi >= 0) target.substring(qi + 1) else "")
                val len = headers["content-length"]?.toLongOrNull() ?: 0L

                if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    out.flush()
                }

                val body = LimitedInputStream(input, len)
                val response = try {
                    handler(Request(parts[0].uppercase(Locale.ROOT), path, query, headers, body, len))
                } catch (e: Exception) {
                    Response.error(500, e.message ?: "error")
                }
                // Let the client finish sending before we answer, otherwise it sees a reset instead of our error.
                if (len in 1..DRAIN_LIMIT) body.drain()
                write(out, response)
            } catch (e: Exception) {
                // client went away or sent garbage: nothing useful to do
            }
        }
    }

    private fun write(out: OutputStream, r: Response) {
        val length = r.bytes?.size?.toLong() ?: r.file?.length() ?: 0L
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        r.bytes?.let { out.write(it) }
        r.file?.let { f -> FileInputStream(f).use { it.copyTo(out, 64 * 1024) } }
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16 * 1024) throw IOException("header too long")
        }
        return sb.toString()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        for (pair in q.split("&")) {
            val i = pair.indexOf('=')
            try {
                if (i < 0) m[URLDecoder.decode(pair, "UTF-8")] = ""
                else m[URLDecoder.decode(pair.substring(0, i), "UTF-8")] = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            } catch (e: IllegalArgumentException) {
                // skip malformed pair
            }
        }
        return m
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        507 -> "Insufficient Storage"
        else -> "OK"
    }

    private class LimitedInputStream(private val src: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val c = src.read()
            if (c >= 0) remaining--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        fun drain() {
            val buf = ByteArray(16 * 1024)
            while (remaining > 0 && read(buf, 0, buf.size) >= 0) {
                // discard
            }
        }
    }

    private companion object {
        const val DRAIN_LIMIT = 64L * 1024 * 1024
    }
}
