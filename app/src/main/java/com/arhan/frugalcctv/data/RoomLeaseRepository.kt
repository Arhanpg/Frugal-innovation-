package com.arhan.frugalcctv.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.client.request.header
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class RoomLeaseRepository(
    private val projectUrl: String,
    private val publishableKey: String,
    roomCode: String,
    stableCameraId: String? = null
) {
    val cameraId: String = stableCameraId ?: UUID.randomUUID().toString()
    private val client = HttpClient(Android)
    private val normalizedRoom = roomCode.trim().uppercase()
    @Volatile private var failureMessage: String? = null

    suspend fun claim(): Boolean = call("claim_cctv_room") == true
    suspend fun heartbeat(): Boolean? = call("heartbeat_cctv_room")
    suspend fun release(): Boolean? = call("release_cctv_room")
    fun lastError(): String? = failureMessage
    fun close() = client.close()

    private suspend fun call(function: String): Boolean? = runCatching {
        val response = client.post("${projectUrl.trimEnd('/')}/rest/v1/rpc/$function") {
            header("apikey", publishableKey)
            if (publishableKey.startsWith("eyJ")) header("Authorization", "Bearer $publishableKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("p_room_code", normalizedRoom)
                put("p_camera_id", cameraId)
            }.toString())
        }
        val body = response.bodyAsText().trim()
        if (!response.status.isSuccess()) {
            failureMessage = "Supabase $function failed (${response.status.value}) ${body.take(220)}"
            return@runCatching null
        }
        when {
            body.equals("true", true) || body.equals("\"true\"", true) -> { failureMessage = null; true }
            body.equals("false", true) || body.equals("\"false\"", true) -> { failureMessage = null; false }
            else -> { failureMessage = "Unexpected Supabase $function response: ${body.take(180)}"; null }
        }
    }.getOrElse { e ->
        failureMessage = e.message?.take(220) ?: "Supabase request failed"
        null
    }
}
