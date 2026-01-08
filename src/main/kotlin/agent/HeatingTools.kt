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
    @LLMDescription("Włącza ogrzewanie dla konkretnego pokoju. Użyj tej funkcji gdy temperatura w pokoju jest zbyt niska lub gdy zbliża się spotkanie w tym pokoju.")
    suspend fun turnOnHeating(
        @LLMDescription("ID pokoju (np. 'room_208', 'room_209')") roomId: String,
        @LLMDescription("Powód włączenia ogrzewania (opcjonalne)") reason: String? = null
    ): String {
        val success = simulatorClient.setRoomHeating(roomId, true)
        return if (success) {
            println("✅ Heating turned ON for room $roomId - ${reason ?: "no reason provided"}")
            "Ogrzewanie włączone dla pokoju $roomId. ${reason ?: ""}"
        } else {
            "Błąd: Nie udało się włączyć ogrzewania dla pokoju $roomId (pokój nie istnieje)."
        }
    }
    
    @Tool
    @LLMDescription("Wyłącza ogrzewanie dla konkretnego pokoju. Użyj tej funkcji gdy temperatura w pokoju jest wystarczająco wysoka lub gdy nie ma osób w pokoju.")
    suspend fun turnOffHeating(
        @LLMDescription("ID pokoju (np. 'room_208', 'room_209')") roomId: String,
        @LLMDescription("Powód wyłączenia ogrzewania (opcjonalne)") reason: String? = null
    ): String {
        val success = simulatorClient.setRoomHeating(roomId, false)
        return if (success) {
            println("❌ Heating turned OFF for room $roomId - ${reason ?: "no reason provided"}")
            "Ogrzewanie wyłączone dla pokoju $roomId. ${reason ?: ""}"
        } else {
            "Błąd: Nie udało się wyłączyć ogrzewania dla pokoju $roomId (pokój nie istnieje)."
        }
    }
    
    @Tool
    @LLMDescription("Wysyła komunikat w języku naturalnym do innego agenta. Użyj tego do komunikacji z innymi agentami w systemie.")
    suspend fun sendMessage(
        @LLMDescription("ID agenta docelowego (np. 'WindowBlindsAgent', 'LightAgent', 'PrinterAgent') lub 'broadcast' dla wszystkich") toAgent: String,
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
            to = toAgent,
            type = messageType,
            content = message,
            context = null
        )

        val success = simulatorClient.sendMessage(messageRequest)
        return if (success) {
            println("📤 Message sent to $toAgent: $message")
            "Wiadomość wysłana do $toAgent."
        } else {
            "Błąd: Nie udało się wysłać wiadomości."
        }
    }
}

