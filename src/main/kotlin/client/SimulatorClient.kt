package com.pawlowski.client

import com.pawlowski.models.AgentMessage
import com.pawlowski.models.AgentMessageRequest
import com.pawlowski.models.ApiResponse
import com.pawlowski.models.EnvironmentState
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SimulatorClient(private val baseUrl: String = "http://localhost:8080") {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000 // 3 minuty dla wywołań LLM
            connectTimeoutMillis = 10_000 // 10 sekund
            socketTimeoutMillis = 180_000 // 3 minuty
        }
    }

    suspend fun getEnvironmentState(): EnvironmentState? {
        return try {
            client.get("$baseUrl/api/environment/state").body()
        } catch (e: Exception) {
            println("Error fetching environment state: ${e.message}")
            null
        }
    }

    suspend fun getHeatingState(): Boolean? {
        return try {
            val response = client.get("$baseUrl/api/environment/heating").body<Map<String, Boolean>>()
            response["isHeating"]
        } catch (e: Exception) {
            println("Error fetching heating state: ${e.message}")
            null
        }
    }

    suspend fun setHeating(isHeating: Boolean): Boolean {
        return try {
            val response = client.post("$baseUrl/api/environment/heating/control") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("isHeating" to isHeating))
            }.body<ApiResponse>()
            response.success
        } catch (e: Exception) {
            println("Error setting heating: ${e.message}")
            false
        }
    }

    suspend fun getMessagesForAgent(agentId: String): List<AgentMessage> {
        return try {
            client.get("$baseUrl/api/environment/agents/messages/$agentId").body()
        } catch (e: Exception) {
            println("Error fetching messages: ${e.message}")
            emptyList()
        }
    }

    suspend fun getNewMessagesForAgent(agentId: String, afterTimestamp: String?): List<AgentMessage> {
        return try {
            val url = if (afterTimestamp != null) {
                "$baseUrl/api/environment/agents/messages/$agentId/new?after=$afterTimestamp"
            } else {
                "$baseUrl/api/environment/agents/messages/$agentId/new"
            }
            client.get(url).body()
        } catch (e: Exception) {
            println("Error fetching new messages: ${e.message}")
            emptyList()
        }
    }

    suspend fun sendMessage(request: AgentMessageRequest): Boolean {
        return try {
            val response = client.post("$baseUrl/api/environment/agents/messages") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<ApiResponse>()
            response.success
        } catch (e: Exception) {
            println("Error sending message: ${e.message}")
            false
        }
    }

    fun close() {
        client.close()
    }
}

