package com.socialgeniex.smsgateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when the server answers with a non-2xx status. */
class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Thin OkHttp wrapper over the SocialGeniex SMS-gateway API.
 * Base URL + token come from the QR code. All calls block — run off the main thread.
 */
class ApiClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    private fun call(path: String, method: String, body: JSONObject? = null): JSONObject {
        val requestBody = if (method == "GET") null
            else (body?.toString() ?: "{}").toRequestBody(jsonType)
        val req = Request.Builder()
            .url("$base$path")
            .header("X-Gateway-Token", token)
            .method(method, requestBody)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, text.take(200))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    @Throws(IOException::class)
    fun register(fcmToken: String?, deviceName: String, sims: JSONArray, appVersion: String): JSONObject {
        val b = JSONObject()
            .put("device_name", deviceName)
            .put("sims", sims)
            .put("app_version", appVersion)
        if (!fcmToken.isNullOrBlank()) b.put("fcm_token", fcmToken)
        return call("/api/sms/gateway/register", "POST", b)
    }

    @Throws(IOException::class)
    fun pull(): JSONArray =
        call("/api/sms/gateway/pull", "GET").optJSONArray("jobs") ?: JSONArray()

    @Throws(IOException::class)
    fun report(jobId: Long, status: String, error: String? = null) {
        val r = JSONObject().put("id", jobId).put("status", status)
        if (error != null) r.put("error", error)
        call(
            "/api/sms/gateway/report", "POST",
            JSONObject().put("results", JSONArray().put(r))
        )
    }

    @Throws(IOException::class)
    fun heartbeat(battery: Int, sims: JSONArray, appVersion: String) {
        call(
            "/api/sms/gateway/heartbeat", "POST", JSONObject()
                .put("battery", battery)
                .put("sims", sims)
                .put("app_version", appVersion)
        )
    }

    @Throws(IOException::class)
    fun inbound(from: String, body: String) {
        call(
            "/api/sms/gateway/inbound", "POST", JSONObject()
                .put("from", from)
                .put("body", body)
        )
    }

    @Throws(IOException::class)
    fun sendTest(toNumber: String, body: String) {
        call(
            "/api/sms/gateway/send", "POST", JSONObject()
                .put("to_number", toNumber)
                .put("body", body)
        )
    }
}
