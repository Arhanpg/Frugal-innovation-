package com.arhan.frugalcctv.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class RoomLeaseRepository(
    private val projectUrl: String,
    private val publishableKey: String,
    roomCode: String
) {
    val cameraId: String = UUID.randomUUID().toString()
    private val client = HttpClient(Android)
    private val normalizedRoom = roomCode.trim().uppercase()

    suspend fun claim(): Boolean = call("claim_cctv_room") == true
    suspend fun heartbeat(): Boolean? = call("heartbeat_cctv_room")
    suspend fun release(): Boolean? = call("release_cctv_room")

    fun close() = client.close()

    private suspend fun call(function: String): Boolean? {
        return runCatching {
            val response = client.post("${projectUrl.trimEnd('/')}/rest/v1/rpc/$function") {
                headers {
                    append("apikey", publishableKey)
                    append("Authorization", "Bearer $publishableKey")
                }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("p_room_code", normalizedRoom)
                    put("p_camera_id", cameraId)
                }.toString())
            }
            response.bodyAsText().trim().equals("true", ignoreCase = true)
        }.getOrNull()
    }
}
