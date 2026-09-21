package knes.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the kNES REST API.
 * MCP tools delegate to the running Compose UI's embedded API server.
 */
class RestApiClient(private val baseUrl: String = "http://localhost:6502") {
    private val json = Json { encodeDefaults = true }

    fun get(path: String): ApiResponse {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        return readResponse(conn)
    }

    fun postJson(path: String, body: String): ApiResponse {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 5000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(conn)
    }

    fun postJson(path: String, body: JsonElement): ApiResponse =
        postJson(path, json.encodeToString(JsonElement.serializer(), body))

    fun postText(path: String, body: String): ApiResponse {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.connectTimeout = 5000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(conn)
    }

    fun isAvailable(): Boolean {
        return try {
            val resp = get("/health")
            resp.code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun readResponse(conn: HttpURLConnection): ApiResponse {
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        return ApiResponse(code, body)
    }
}

data class ApiResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}
