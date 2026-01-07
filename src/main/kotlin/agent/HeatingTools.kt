package com.pawlowski.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.pawlowski.client.SimulatorClient
import com.pawlowski.models.AgentMessageRequest
import com.pawlowski.models.MessageType

class HeatingTools(
    private val simulatorClient: SimulatorClient,
    private val agentId: String
) : ToolSet {
    
    @Tool
    @LLMDescription("Włącza ogrzewanie w budynku. Użyj tej funkcji gdy temperatura w pokojach jest zbyt niska lub gdy zbliża się spotkanie.")
    suspend fun turnOnHeating(
        @LLMDescription("Powód włączenia ogrzewania (opcjonalne)") reason: String? = null
    ): String {
        val success = simulatorClient.setHeating(true)
        return if (success) {
            println("✅ Heating turned ON - ${reason ?: "no reason provided"}")
            "Ogrzewanie włączone. ${reason ?: ""}"
        } else {
            "Błąd: Nie udało się włączyć ogrzewania."
        }
    }
    
    @Tool
    @LLMDescription("Wyłącza ogrzewanie w budynku. Użyj tej funkcji gdy temperatura w pokojach jest wystarczająco wysoka lub gdy nie ma osób w budynku.")
    suspend fun turnOffHeating(
        @LLMDescription("Powód wyłączenia ogrzewania (opcjonalne)") reason: String? = null
    ): String {
        val success = simulatorClient.setHeating(false)
        return if (success) {
            println("❌ Heating turned OFF - ${reason ?: "no reason provided"}")
            "Ogrzewanie wyłączone. ${reason ?: ""}"
        } else {
            "Błąd: Nie udało się wyłączyć ogrzewania."
        }
    }
    
    @Tool
    @LLMDescription("Wysyła komunikat w języku naturalnym do innego agenta. Użyj tego do komunikacji z innymi agentami w systemie.")
    suspend fun sendMessage(
        @LLMDescription("ID agenta docelowego (np. 'WindowBlindsAgent', 'LightAgent', 'PrinterAgent') lub 'broadcast' dla wszystkich") to: String,
        @LLMDescription("Treść wiadomości w języku naturalnym") message: String,
        @LLMDescription("Typ wiadomości: 'INFORM', 'REQUEST', 'QUERY', 'RESPONSE' (domyślnie 'INFORM')") type: String = "INFORM"
    ): String {
        val messageType = when (type.uppercase()) {
            "REQUEST" -> MessageType.REQUEST
            "QUERY" -> MessageType.QUERY
            "RESPONSE" -> MessageType.RESPONSE
            else -> MessageType.INFORM
        }

        val messageRequest = AgentMessageRequest(
            from = agentId,
            to = to,
            type = messageType,
            content = message,
            context = null
        )

        val success = simulatorClient.sendMessage(messageRequest)
        return if (success) {
            println("📤 Message sent to $to: $message")
            "Wiadomość wysłana do $to."
        } else {
            "Błąd: Nie udało się wysłać wiadomości."
        }
    }
}

