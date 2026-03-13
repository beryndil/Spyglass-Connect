package com.spyglass.connect.pterodactyl

import com.spyglass.connect.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HTTP client for the Pterodactyl Panel API (client API, not application API).
 * Uses Bearer token auth with `ptlc_` prefixed API keys.
 */
class PterodactylClient(
    private val panelUrl: String,
    private val apiKey: String,
) {
    companion object {
        private const val TAG = "Ptero"
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 30_000
        }
    }

    private val baseUrl = panelUrl.trimEnd('/')

    /** Test connection by listing servers. Returns server count on success. */
    suspend fun testConnection(): Result<Int> {
        return try {
            val servers = listServers()
            Result.success(servers.size)
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** List all servers the API key has access to (handles pagination). */
    suspend fun listServers(): List<PteroServer> {
        val servers = mutableListOf<PteroServer>()
        var page = 1

        while (true) {
            val response = client.get("$baseUrl/api/client") {
                header("Authorization", "Bearer $apiKey")
                header("Accept", "Application/vnd.pterodactyl.v1+json")
                parameter("page", page)
            }

            if (response.status != HttpStatusCode.OK) {
                throw PterodactylException("API returned ${response.status}: ${response.bodyAsText()}")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]?.jsonArray ?: break

            for (item in data) {
                val attrs = item.jsonObject["attributes"]?.jsonObject ?: continue
                servers.add(
                    PteroServer(
                        identifier = attrs["identifier"]?.jsonPrimitive?.content ?: continue,
                        name = attrs["name"]?.jsonPrimitive?.content ?: "Unknown",
                        uuid = attrs["uuid"]?.jsonPrimitive?.content ?: "",
                    )
                )
            }

            // Check pagination
            val meta = body["meta"]?.jsonObject?.get("pagination")?.jsonObject
            val totalPages = meta?.get("total_pages")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            if (page >= totalPages) break
            page++
        }

        Log.i(TAG, "Found ${servers.size} servers")
        return servers
    }

    /** List files in a directory on a server. */
    suspend fun listFiles(serverId: String, directory: String = "/"): List<PteroFile> {
        val response = client.get("$baseUrl/api/client/servers/$serverId/files/list") {
            header("Authorization", "Bearer $apiKey")
            header("Accept", "Application/vnd.pterodactyl.v1+json")
            parameter("directory", directory)
        }

        if (response.status != HttpStatusCode.OK) {
            throw PterodactylException("List files failed (${response.status}): ${response.bodyAsText()}")
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonArray ?: return emptyList()

        return data.map { item ->
            val attrs = item.jsonObject["attributes"]?.jsonObject
                ?: throw PterodactylException("Missing attributes in file list")
            PteroFile(
                name = attrs["name"]?.jsonPrimitive?.content ?: "",
                isFile = attrs["is_file"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                size = attrs["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                modifiedAt = attrs["modified_at"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    /** Download a file's raw contents from a server. */
    suspend fun downloadFile(serverId: String, filePath: String): ByteArray {
        val response = client.get("$baseUrl/api/client/servers/$serverId/files/contents") {
            header("Authorization", "Bearer $apiKey")
            header("Accept", "Application/vnd.pterodactyl.v1+json")
            parameter("file", filePath)
        }

        if (response.status != HttpStatusCode.OK) {
            throw PterodactylException("Download failed for $filePath (${response.status})")
        }

        return response.readRawBytes()
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class PteroServer(
    val identifier: String,
    val name: String,
    val uuid: String = "",
)

data class PteroFile(
    val name: String,
    val isFile: Boolean,
    val size: Long,
    val modifiedAt: String,
)

class PterodactylException(message: String) : Exception(message)
