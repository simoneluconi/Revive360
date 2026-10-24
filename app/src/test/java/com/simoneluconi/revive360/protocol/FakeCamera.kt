package com.simoneluconi.revive360.protocol

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection

/**
 * A software camera server speaking docs/protocol.md, on localhost, for the client tests. It holds a
 * few files in memory and records the requests it saw.
 */
class FakeCamera : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val files = linkedMapOf(
        "DCIM/100PHOTO/360_0042.SRW" to ByteArray(300_000) { (it % 251).toByte() },
        "DCIM/100PHOTO/360_0042.JPG" to ByteArray(120_000) { (it % 13).toByte() },
    )
    val requests = mutableListOf<String>()
    var busy = false
    var lastTimeBody: String? = null

    val port: Int get() = server.address.port
    val client: CameraClient get() = CameraClient("127.0.0.1", port, Connector { it.openConnection() as HttpURLConnection })

    init {
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val method = ex.requestMethod
        val rawPath = ex.requestURI.rawPath
        val path = ex.requestURI.path
        requests += "$method $rawPath${ex.requestHeaders.getFirst("Range")?.let { " [$it]" } ?: ""}"
        when {
            method == "GET" && path == "/api/v1/info" -> json(ex, 200, INFO)
            method == "GET" && path == "/api/v1/status" -> json(ex, 200, STATUS)
            method == "GET" && path == "/api/v1/files" -> json(ex, 200, FILES)
            method == "POST" && path == "/api/v1/capture" -> {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                when {
                    busy -> error(ex, 409, "busy")
                    "\"photo\"" in body -> json(ex, 200, """{"files":[{"path":"DCIM/100PHOTO/360_0043.JPG","name":"360_0043.JPG","size":5,"mtime":1,"kind":"photo"}],"duration_ms":2410}""")
                    else -> error(ex, 501, "unsupported")
                }
            }
            method == "POST" && path == "/api/v1/led" -> json(ex, 200, """{"id":"front","state":"on"}""")
            method == "POST" && path == "/api/v1/time" -> {
                lastTimeBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                json(ex, 200, """{"utc_ms":1729780000123,"applied":["system","rtc"]}""")
            }
            path.startsWith("/api/v1/thumb/") -> {
                val file = path.removePrefix("/api/v1/thumb/")
                if (file.endsWith(".JPG")) bytes(ex, 200, byteArrayOf(-1, -40, -1, -39)) else error(ex, 404, "no_thumbnail")
            }
            path.startsWith("/files/") -> {
                val file = path.removePrefix("/files/")
                val data = files[file] ?: return error(ex, 404, "not_found")
                when (method) {
                    "DELETE" -> {
                        if (busy) return error(ex, 409, "busy")
                        files.remove(file)
                        json(ex, 200, """{"deleted":"$file"}""")
                    }
                    else -> download(ex, data)
                }
            }
            else -> error(ex, 404, "not_found")
        }
    }

    private fun download(ex: HttpExchange, data: ByteArray) {
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
        if (range == null) return bytes(ex, 200, data)
        val start = range.substringBefore('-').toInt()
        val end = range.substringAfter('-').toIntOrNull() ?: (data.size - 1)
        if (start >= data.size) {
            ex.responseHeaders.add("Content-Range", "bytes */${data.size}")
            return error(ex, 416, "range_not_satisfiable")
        }
        ex.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
        bytes(ex, 206, data.copyOfRange(start, end + 1))
    }

    private fun json(ex: HttpExchange, code: Int, body: String) {
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        bytes(ex, code, body.toByteArray())
    }

    private fun error(ex: HttpExchange, code: Int, error: String) =
        json(ex, code, """{"error":{"code":"$error","message":"fake $error"}}""")

    private fun bytes(ex: HttpExchange, code: Int, body: ByteArray) {
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
    }

    companion object {
        /** The example of protocol §5.1 with the capabilities of §4. */
        val INFO = """
            {"protocol":1,"server":{"name":"g360rd","version":"0.1.0"},
             "camera":{"model":"SM-C200","model_raw":"SMC200","firmware":"C200GLU0AQK1","adapter":"c200",
                       "adapter_status":"supported","mac":"A0:B1:C2:65:EF:1E","name":"Gear 360 (65:EF:1E)"},
             "capabilities":{
               "capture":{"modes":["photo","raw","raw+photo"],"photo_qualities":["sfine","fine","norm"],
                          "photo_resolutions":["7776x3888"],"resolution_settable":false},
               "video_modes":["3840x1920@30"],"record":false,"liveview":false,
               "files":{"list":true,"download":true,"range":true,"delete":true,"thumbnails":true},
               "status":{"battery":true,"storage":true,"battery_temperature":true},
               "leds":[{"id":"status_r","label":"Status (red)","role":"status_red","states":["off","on","blink","auto"]},
                       {"id":"front","label":"Front","role":"front","states":["off","on"]}],
               "debug_log":true}}
        """.trimIndent()

        val STATUS = """
            {"battery":{"percent":87,"charging":false,"status":"discharging","external_power":false,"temperature_c":31.5},
             "storage":{"mounted":true,"total_bytes":127865454592,"free_bytes":120000000000,"used_bytes":7865454592},
             "busy":false,"uptime_s":312}
        """.trimIndent()

        val FILES = """
            {"files":[
              {"path":"DCIM/100PHOTO/360_0042.SRW","name":"360_0042.SRW","size":300000,"mtime":1729780000,"kind":"raw"},
              {"path":"DCIM/100PHOTO/360_0042.JPG","name":"360_0042.JPG","size":120000,"mtime":1729780000,"kind":"photo"},
              {"path":"DCIM/100VIDEO/360_0007.MP4","name":"360_0007.MP4","size":98765432,"mtime":1729700000,"kind":"video"}]}
        """.trimIndent()
    }
}
