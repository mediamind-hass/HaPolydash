package com.example.myapplication.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

data class EntityStateResult(
    val entityId: String,
    val state: String,
    val unitOfMeasurement: String? = null,
    val friendlyName: String? = null
)

sealed class ConnectionResult {
    object Success : ConnectionResult()
    data class Timeout(val url: String, val suggestedUrl: String? = null) : ConnectionResult()
    data class Unauthorized(val code: Int) : ConnectionResult()
    data class Error(val message: String, val suggestedUrl: String? = null) : ConnectionResult()
}

class HomeAssistantApi(
    private val prefs: HaPreferences,
    private val context: Context? = null
) {

    init {
        // Trust all SSL certificates for local HTTPS setup
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e("HA_API", "Error configuring HttpsURLConnection TrustAllCerts: ${e.localizedMessage}")
        }
    }

    private fun executeCallUrlConnection(path: String, isPost: Boolean = false, jsonBodyStr: String? = null, timeoutMs: Int = 12000): Pair<Int, String?> {
        var serverUrl = prefs.serverUrl.trim().trimEnd('/')
        val token = prefs.token.trim()
        if (serverUrl.isBlank() || token.isBlank()) return Pair(-1, null)

        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://$serverUrl"
        }

        val formattedPath = if (path.startsWith("http")) path else if (path.startsWith("/")) path else "/$path"
        val fullUrl = if (path.startsWith("http")) path else "$serverUrl$formattedPath"

        Log.d("HA_API", "HttpURLConnection executing: $fullUrl (isPost=$isPost)")

        val maxRetries = 2
        for (attempt in 1..maxRetries) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(fullUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; SM-F761B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

                if (isPost) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Expect", "") // Disable 100-continue to prevent deadlock with TrueNAS aiohttp
                    val bodyData = (jsonBodyStr ?: "{}").toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bodyData.size)
                    connection.outputStream.use { os ->
                        os.write(bodyData)
                        os.flush()
                    }
                } else {
                    connection.requestMethod = "GET"
                }

                val statusCode = try {
                    connection.responseCode
                } catch (e: java.net.SocketTimeoutException) {
                    if (isPost) {
                        Log.d("HA_API", "POST request delivered; response read timed out while script runs. Treating as HTTP 200 SUCCESS.")
                        200
                    } else {
                        throw e
                    }
                }
                Log.d("HA_API", "HttpURLConnection response code: $statusCode for $fullUrl")

                val stream = try {
                    if (statusCode in 200..299) connection.inputStream else connection.errorStream
                } catch (_: Exception) {
                    null
                }
                val bodyStr = stream?.bufferedReader()?.use { it.readText() }
                return Pair(statusCode, bodyStr)
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("HA_API", "SocketTimeoutException on attempt $attempt/$maxRetries for $fullUrl: ${e.localizedMessage}")
                if (attempt >= maxRetries) {
                    Log.e("HA_API", "HttpURLConnection socket timeout exception for $fullUrl after $maxRetries attempts: ${e.localizedMessage}", e)
                    return Pair(-1, null)
                }
                try { Thread.sleep(500) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("HA_API", "HttpURLConnection exception for $fullUrl: ${e.localizedMessage}", e)
                return Pair(-1, null)
            } finally {
                connection?.disconnect()
            }
        }
        return Pair(-1, null)
    }

    suspend fun fetchAllStatesMap(timeoutMs: Int = 12000): Map<String, EntityStateResult> = withContext(Dispatchers.IO) {
        try {
            val (code, bodyStr) = executeCallUrlConnection("/api/states", timeoutMs = timeoutMs)
            if (code !in 200..299 || bodyStr == null) {
                Log.e("HA_API", "fetchAllStatesMap failed with HTTP code: $code")
                return@withContext emptyMap()
            }
            val resultMap = mutableMapOf<String, EntityStateResult>()
            val jsonArray = JSONArray(bodyStr)
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.optJSONObject(i) ?: continue
                val entityId = json.optString("entity_id", "")
                if (entityId.isBlank()) continue
                val state = json.optString("state", "N/A")
                val attributes = json.optJSONObject("attributes")
                val unit = if (attributes?.has("unit_of_measurement") == true) attributes.optString("unit_of_measurement") else null
                val friendlyName = if (attributes?.has("friendly_name") == true) attributes.optString("friendly_name") else null
                resultMap[entityId] = EntityStateResult(
                    entityId = entityId,
                    state = state,
                    unitOfMeasurement = unit,
                    friendlyName = friendlyName
                )
            }
            Log.d("HA_API", "fetchAllStatesMap SUCCESS! Loaded ${resultMap.size} entities in 1 single HTTP request.")
            resultMap
        } catch (e: Exception) {
            Log.e("HA_API", "Exception in fetchAllStatesMap: ${e.localizedMessage}", e)
            emptyMap()
        }
    }

    suspend fun fetchEntityState(rawEntityId: String): EntityStateResult? = withContext(Dispatchers.IO) {
        val trimmed = rawEntityId.trim()
        if (trimmed.isBlank()) return@withContext null
        val entityId = if (!trimmed.contains(".")) "sensor.$trimmed" else trimmed
        try {
            val (code, bodyStr) = executeCallUrlConnection("/api/states/$entityId")
            if (code !in 200..299 || bodyStr == null) {
                Log.e("HA_API", "fetchEntityState($entityId) failed with HTTP code: $code")
                return@withContext null
            }
            val json = JSONObject(bodyStr)
            val state = json.optString("state", "N/A")
            val attributes = json.optJSONObject("attributes")
            val unit = if (attributes?.has("unit_of_measurement") == true) attributes.optString("unit_of_measurement") else null
            val friendlyName = if (attributes?.has("friendly_name") == true) attributes.optString("friendly_name") else null
            EntityStateResult(
                entityId = entityId,
                state = state,
                unitOfMeasurement = unit,
                friendlyName = friendlyName
            )
        } catch (e: Exception) {
            Log.e("HA_API", "Exception in fetchEntityState($entityId): ${e.localizedMessage}", e)
            null
        }
    }

    suspend fun callEntityService(rawEntityId: String, actionService: String = "toggle"): Boolean = withContext(Dispatchers.IO) {
        val trimmed = rawEntityId.trim()
        if (trimmed.isBlank()) return@withContext false
        val entityId = if (!trimmed.contains(".")) "switch.$trimmed" else trimmed
        try {
            val parts = entityId.split(".", limit = 2)
            if (parts.size < 2) return@withContext false
            val domain = parts[0]
            val entityName = parts[1]

            val jsonBody = JSONObject().apply {
                put("entity_id", entityId)
            }
            val jsonStr = jsonBody.toString()

            val actualService = if (actionService == "toggle") {
                when (domain) {
                    "script" -> "turn_on"
                    "automation" -> "trigger"
                    "scene" -> "turn_on"
                    "button", "input_button" -> "press"
                    else -> "toggle"
                }
            } else {
                actionService
            }

            // Attempt 1: /api/services/domain/actualService
            Log.d("HA_API", "callEntityService attempt 1: POST to /api/services/$domain/$actualService with body: $jsonStr")
            val (code, responseBody) = executeCallUrlConnection("/api/services/$domain/$actualService", isPost = true, jsonBodyStr = jsonStr)
            if (code in 200..299) {
                Log.d("HA_API", "callEntityService($entityId, $actualService) SUCCESS! Response: $responseBody")
                return@withContext true
            }

            // Attempt 2: Universal service /api/services/homeassistant/turn_on or /api/services/homeassistant/toggle
            Log.d("HA_API", "callEntityService attempt 2: POST to /api/services/homeassistant/turn_on")
            val (code2, responseBody2) = executeCallUrlConnection("/api/services/homeassistant/turn_on", isPost = true, jsonBodyStr = jsonStr)
            if (code2 in 200..299) {
                Log.d("HA_API", "callEntityService homeassistant/turn_on SUCCESS! Response: $responseBody2")
                return@withContext true
            }

            // Attempt 3 for scripts: /api/services/script/<script_name>
            if (domain == "script") {
                Log.d("HA_API", "callEntityService attempt 3 for script: POST to /api/services/script/$entityName")
                val (fallbackCode, responseBody3) = executeCallUrlConnection("/api/services/script/$entityName", isPost = true, jsonBodyStr = jsonStr)
                if (fallbackCode in 200..299) {
                    Log.d("HA_API", "callEntityService fallback script/$entityName SUCCESS! Response: $responseBody3")
                    return@withContext true
                }
            }

            Log.e("HA_API", "callEntityService($entityId) failed. Final HTTP code: $code")
            false
        } catch (e: Exception) {
            Log.e("HA_API", "Exception in callEntityService($entityId): ${e.localizedMessage}", e)
            false
        }
    }

    suspend fun fetchCameraSnapshot(rawCameraEntityId: String, timeoutMs: Int = 12000): Bitmap? = withContext(Dispatchers.IO) {
        val cameraEntityId = rawCameraEntityId.trim().let {
            if (it.isNotBlank() && !it.contains(".")) "camera.$it" else it
        }
        if (cameraEntityId.isBlank()) return@withContext null

        val directBitmap = downloadImageFromPath("/api/camera_proxy/$cameraEntityId", timeoutMs = timeoutMs)
        if (directBitmap != null) return@withContext directBitmap

        try {
            val (code, bodyStr) = executeCallUrlConnection("/api/states/$cameraEntityId", timeoutMs = timeoutMs)
            if (code in 200..299 && bodyStr != null) {
                val json = JSONObject(bodyStr)
                val attributes = json.optJSONObject("attributes")
                val entityPicture = if (attributes?.has("entity_picture") == true) attributes.optString("entity_picture") else null
                if (!entityPicture.isNullOrBlank()) {
                    return@withContext downloadImageFromPath(entityPicture, timeoutMs = timeoutMs)
                }
            } else {
                Log.e("HA_API", "fetchCameraSnapshot($cameraEntityId) failed HTTP code: $code")
            }
        } catch (e: Exception) {
            Log.e("HA_API", "Exception in fetchCameraSnapshot($cameraEntityId): ${e.localizedMessage}", e)
        }

        null
    }

    private fun downloadImageFromPath(path: String, timeoutMs: Int = 12000): Bitmap? {
        var serverUrl = prefs.serverUrl.trim().trimEnd('/')
        val token = prefs.token.trim()
        if (serverUrl.isBlank() || token.isBlank()) return null
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://$serverUrl"
        }
        val formattedPath = if (path.startsWith("http")) path else if (path.startsWith("/")) path else "/$path"
        val fullUrl = if (path.startsWith("http")) path else "$serverUrl$formattedPath"

        val maxRetries = 2
        for (attempt in 1..maxRetries) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(fullUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; SM-F761B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

                val statusCode = connection.responseCode
                val stream = try {
                    if (statusCode in 200..299) connection.inputStream else connection.errorStream
                } catch (_: Exception) {
                    null
                }

                if (statusCode !in 200..299 || stream == null) {
                    Log.e("HA_API", "downloadImageFromPath failed with HTTP code $statusCode for $fullUrl")
                    if (attempt >= maxRetries) return null
                    continue
                }

                stream.use { s ->
                    val bytes = s.readBytes()
                    if (bytes.isEmpty()) return null
                    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

                    val maxWidth = 400
                    val maxHeight = 250
                    return if (rawBitmap.width > maxWidth || rawBitmap.height > maxHeight) {
                        val scale = minOf(maxWidth.toFloat() / rawBitmap.width, maxHeight.toFloat() / rawBitmap.height)
                        val scaledW = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
                        val scaledH = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(rawBitmap, scaledW, scaledH, true)
                    } else {
                        rawBitmap
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("HA_API", "downloadImageFromPath SocketTimeoutException on attempt $attempt/$maxRetries for $fullUrl: ${e.localizedMessage}")
                if (attempt >= maxRetries) {
                    Log.e("HA_API", "downloadImageFromPath socket timeout exception for $fullUrl after $maxRetries attempts: ${e.localizedMessage}", e)
                    return null
                }
                try { Thread.sleep(500) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("HA_API", "downloadImageFromPath exception for $fullUrl: ${e.localizedMessage}", e)
                return null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun extractHost(urlStr: String): String? {
        return try {
            val uri = java.net.URI(if (!urlStr.startsWith("http")) "http://$urlStr" else urlStr)
            uri.host
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPort(urlStr: String): Int {
        return try {
            val uri = java.net.URI(if (!urlStr.startsWith("http")) "http://$urlStr" else urlStr)
            if (uri.port != -1) uri.port else if (urlStr.startsWith("https")) 443 else 80
        } catch (_: Exception) {
            80
        }
    }

    private fun probeUrl(urlStr: String): Boolean {
        return try {
            val token = prefs.token.trim()
            if (token.isBlank()) return false
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; SM-F761B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            val statusCode = connection.responseCode
            connection.disconnect()
            statusCode in 200..299 || statusCode == 401
        } catch (_: Exception) {
            false
        }
    }

    suspend fun testConnectionDetails(): ConnectionResult = withContext(Dispatchers.IO) {
        val mainResult = try {
            val (code, _) = executeCallUrlConnection("/api/")
            if (code in 200..299) {
                Log.d("HA_API", "testConnection SUCCESS!")
                ConnectionResult.Success
            } else if (code == 401) {
                Log.e("HA_API", "testConnection failed 401 Unauthorized")
                ConnectionResult.Unauthorized(code)
            } else {
                Log.e("HA_API", "testConnection failed HTTP code: $code")
                ConnectionResult.Error("HTTP $code")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e("HA_API", "testConnection SocketTimeoutException: ${e.localizedMessage}", e)
            ConnectionResult.Timeout(prefs.serverUrl)
        } catch (e: Exception) {
            Log.e("HA_API", "Exception in testConnection: ${e.localizedMessage}", e)
            ConnectionResult.Error(e.localizedMessage ?: "Errore di connessione")
        }

        // If main URL timed out or failed, probe alternative standard ports on the host
        if (mainResult is ConnectionResult.Timeout || mainResult is ConnectionResult.Error) {
            val host = extractHost(prefs.serverUrl)
            if (!host.isNullOrBlank()) {
                val candidatePorts = listOf(8123, 18888, 80, 443, 8443, 30103)
                val currentPort = extractPort(prefs.serverUrl)
                for (port in candidatePorts) {
                    if (port == currentPort) continue
                    val scheme = if (port == 443 || port == 8443) "https" else "http"
                    val testUrl = "$scheme://$host:$port/api/"
                    Log.d("HA_API", "Probing alternative port: $testUrl")
                    if (probeUrl(testUrl)) {
                        Log.d("HA_API", "FOUND Home Assistant on alternative port: $testUrl")
                        val suggested = "$scheme://$host:$port"
                        return@withContext if (mainResult is ConnectionResult.Timeout) {
                            ConnectionResult.Timeout(prefs.serverUrl, suggestedUrl = suggested)
                        } else {
                            ConnectionResult.Error("Errore su porta originale. Trovato Home Assistant su $suggested", suggestedUrl = suggested)
                        }
                    }
                }
            }
        }

        return@withContext mainResult
    }

    suspend fun testConnection(): Boolean {
        return testConnectionDetails() == ConnectionResult.Success
    }
}
