package com.pawlowski.agent

import com.pawlowski.client.SimulatorClient
import com.pawlowski.models.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HeatingAgent(
    private val simulatorClient: SimulatorClient,
    private val agentId: String = "heating_agent",
    private val decisionIntervalSeconds: Long = 10,
    private val messageCheckIntervalSeconds: Long = 3
) {
    private var running = false
    private var lastDecisionTime = 0L
    private var lastMessageTimestamp: String? = null
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val systemPrompt = """
Jesteś agentem ogrzewania w inteligentnym biurze. Twoim zadaniem jest zarządzanie ogrzewaniem w budynku.

Możesz wykonywać następujące akcje:
- turn_on_heating() - włącz ogrzewanie
- turn_off_heating() - wyłącz ogrzewanie
- send_message(to_agent, message) - wyślij komunikat w języku naturalnym do innego agenta

Zasady działania:
1. Włącz ogrzewanie gdy temperatura w jakimkolwiek pokoju < 21°C i są osoby w pokoju
2. Włącz ogrzewanie 15 minut przed zaplanowanym spotkaniem
3. Wyłącz ogrzewanie gdy wszystkie pokoje mają temperaturę >= 22°C (gdy są osoby) lub >= 18°C (gdy nie ma osób)
4. Utrzymuj minimum 17°C gdy nie ma osób (zapobieganie zamarzaniu)
5. Oszczędzaj energię - wyłącz ogrzewanie gdy nie jest potrzebne

Dostępni agenci do komunikacji:
- WindowBlindsAgent: kontroluje rolety okienne (ochrona przed upałem, światło dzienne)
- LightAgent: kontroluje światła (włącza/wyłącza)
- PrinterAgent: kontroluje drukarki (włącza/wyłącza, zarządza zasobami)

Gdy zmieniasz stan ogrzewania, rozważ czy powinieneś poinformować innych agentów w języku naturalnym.
Na przykład: "Włączyłem ogrzewanie, ponieważ temperatura w pokoju 208 wynosi 19°C i są 2 osoby."
""".trimIndent()

    private val messageProcessingPrompt = """
Jesteś agentem ogrzewania. Otrzymałeś komunikat w języku naturalnym od innego agenta.

Możesz wykonywać akcje:
- turn_on_heating() - włącz ogrzewanie
- turn_off_heating() - wyłącz ogrzewanie
- send_message(to_agent, message) - odpowiedz innemu agentowi

Przeanalizuj komunikat i zdecyduj czy powinieneś zareagować. Jeśli tak, wykonaj odpowiednią akcję.
""".trimIndent()

    suspend fun start() {
        running = true
        println("🔥 HeatingAgent AI started - Agent ID: $agentId")
        
        // Uruchom dwie równoległe pętle
        coroutineScope {
            launch {
                decisionLoop()
            }
            launch {
                messageCheckLoop()
            }
        }
    }

    fun stop() {
        running = false
        println("🛑 HeatingAgent AI stopped")
    }

    private suspend fun decisionLoop() {
        while (running) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastDecisionTime >= decisionIntervalSeconds * 1000) {
                    awaitDecisionCycle()
                    lastDecisionTime = now
                }
                delay(2000) // Check every 2 seconds
            } catch (e: Exception) {
                println("Error in decision loop: ${e.message}")
                delay(5000)
            }
        }
    }

    private suspend fun messageCheckLoop() {
        while (running) {
            try {
                awaitMessageCheck()
                delay(messageCheckIntervalSeconds * 1000)
            } catch (e: Exception) {
                println("Error in message check loop: ${e.message}")
                delay(5000)
            }
        }
    }

    private suspend fun awaitDecisionCycle() {
        val state = simulatorClient.getEnvironmentState()
        if (state == null) {
            println("⚠️ Nie udało się pobrać stanu środowiska - pomijam cykl")
            return
        }
        
        val currentHeating = simulatorClient.getHeatingState()
        if (currentHeating == null) {
            println("⚠️ Nie udało się pobrać stanu ogrzewania - pomijam cykl")
            return
        }

        if (state.powerOutage) {
            println("⚠️ Power outage - heating unavailable")
            return
        }

        println("🔄 Cykl decyzyjny - temp zew: ${state.externalTemperature}°C, ogrzewanie: ${if (currentHeating) "ON" else "OFF"}")
        
        // Buduj prompt z aktualnym stanem
        val prompt = buildDecisionPrompt(state, currentHeating)

        // Wywołaj LLM
        try {
            val llmResponse = simulatorClient.callLLM(prompt, systemPrompt)
            if (llmResponse != null) {
                println("🤖 LLM Response otrzymana (${llmResponse.length} chars)")
                if (llmResponse.length < 200) {
                    println("   Treść: $llmResponse")
                } else {
                    println("   Treść (pierwsze 200 znaków): ${llmResponse.take(200)}...")
                }
                
                // Parsuj odpowiedź LLM i wykonaj akcje
                processLLMResponse(llmResponse, state, currentHeating)
            } else {
                println("⚠️ LLM nie odpowiedział - pomijam cykl")
                // Nie wykonujemy żadnej akcji gdy LLM nie odpowiada
            }
        } catch (e: Exception) {
            println("❌ Błąd w cyklu decyzyjnym: ${e.javaClass.simpleName} - ${e.message}")
            // Nie wykonujemy żadnej akcji gdy wystąpi błąd
        }
    }

    private fun buildDecisionPrompt(state: EnvironmentState, currentHeating: Boolean): String {
        val roomsInfo = state.rooms.joinToString("\n") { room ->
            """
            Pokój ${room.name} (${room.id}):
            - Temperatura: ${room.temperatureSensor.temperature}°C
            - Osoby: ${room.peopleCount}
            - Spotkania: ${if (room.scheduledMeetings.isNotEmpty()) {
                room.scheduledMeetings.joinToString(", ") { 
                    "${it.title} (${it.startTime} - ${it.endTime})"
                }
            } else "brak"}
            """.trimIndent()
        }

        return """
Aktualny stan środowiska:
- Czas symulacji: ${state.simulationTime}
- Temperatura zewnętrzna: ${state.externalTemperature}°C
- Ogrzewanie: ${if (currentHeating) "WŁĄCZONE" else "WYŁĄCZONE"}
- Awaria zasilania: ${if (state.powerOutage) "TAK" else "NIE"}

Pokoje:
$roomsInfo

Przeanalizuj sytuację i zdecyduj czy powinieneś:
1. Włączyć ogrzewanie
2. Wyłączyć ogrzewanie
3. Wysłać komunikat do innego agenta
4. Nic nie robić (utrzymać obecny stan)

Odpowiedz w formacie JSON z akcjami do wykonania.
""".trimIndent()
    }

    private suspend fun awaitMessageCheck() {
        val messages = if (lastMessageTimestamp != null) {
            simulatorClient.getNewMessagesForAgent(agentId, lastMessageTimestamp)
        } else {
            simulatorClient.getMessagesForAgent(agentId)
        }

        if (messages.isNotEmpty()) {
            // Zaktualizuj timestamp ostatniej wiadomości
            lastMessageTimestamp = messages.maxByOrNull { it.timestamp }?.timestamp

            for (message in messages) {
                if (message.to == agentId || message.to == "broadcast") {
                    println("📨 Received message from ${message.from}: ${message.content}")
                    awaitProcessMessage(message)
                }
            }
        }
    }

    private suspend fun awaitProcessMessage(message: AgentMessage) {
        val state = simulatorClient.getEnvironmentState() ?: return
        val currentHeating = simulatorClient.getHeatingState() ?: return

        val prompt = """
$messageProcessingPrompt

Otrzymany komunikat:
Od: ${message.from}
Treść: "${message.content}"
Kontekst: ${message.context ?: "brak"}

Aktualny stan:
- Ogrzewanie: ${if (currentHeating) "WŁĄCZONE" else "WYŁĄCZONE"}
- Temperatura zewnętrzna: ${state.externalTemperature}°C
- Pokoje: ${state.rooms.joinToString(", ") { "${it.name} (${it.temperatureSensor.temperature}°C, ${it.peopleCount} os.)" }}

Zdecyduj czy i jak zareagować na ten komunikat.
""".trimIndent()

        // Wywołaj LLM do przetworzenia komunikatu
        try {
            val llmResponse = simulatorClient.callLLM(prompt, messageProcessingPrompt)
            if (llmResponse != null) {
                println("🤖 LLM Response to message: $llmResponse")
                processLLMMessageResponse(llmResponse, message, state, currentHeating)
            } else {
                println("⚠️ LLM nie odpowiedział na komunikat")
            }
        } catch (e: Exception) {
            println("❌ Error calling LLM for message: ${e.message}")
            e.printStackTrace()
        }
    }

    // Prosta analiza stanu (tymczasowo, przed integracją z LLM)
    private fun analyzeStateForHeating(state: EnvironmentState, currentHeating: Boolean): Boolean {
        for (room in state.rooms) {
            val temp = room.temperatureSensor.temperature
            val peopleCount = room.peopleCount
            
            // Proste reguły (tymczasowe)
            if (peopleCount > 0 && temp < 21.0) return true
            if (peopleCount == 0 && temp < 17.0) return true
            
            // Sprawdź spotkania
            val now = LocalDateTime.parse(state.simulationTime, formatter)
            for (meeting in room.scheduledMeetings) {
                val startTime = LocalDateTime.parse(meeting.startTime, formatter)
                val minutesUntil = java.time.Duration.between(now, startTime).toMinutes()
                if (minutesUntil in 0..15) return true
            }
        }
        
        // Wyłącz jeśli wszystkie pokoje mają odpowiednią temperaturę
        val allComfortable = state.rooms.all { room ->
            val temp = room.temperatureSensor.temperature
            val peopleCount = room.peopleCount
            if (peopleCount > 0) temp >= 22.0 else temp >= 18.0
        }
        
        return !allComfortable
    }

    // Tools dla LLM
    @Serializable
    data class TurnOnHeatingRequest(val reason: String? = null)

    private suspend fun turnOnHeatingTool(request: TurnOnHeatingRequest): String {
        val success = simulatorClient.setHeating(true)
        return if (success) {
            println("✅ Heating turned ON - ${request.reason ?: "no reason provided"}")
            "Ogrzewanie włączone. ${request.reason ?: ""}"
        } else {
            "Błąd: Nie udało się włączyć ogrzewania."
        }
    }

    @Serializable
    data class TurnOffHeatingRequest(val reason: String? = null)

    private suspend fun turnOffHeatingTool(request: TurnOffHeatingRequest): String {
        val success = simulatorClient.setHeating(false)
        return if (success) {
            println("❌ Heating turned OFF - ${request.reason ?: "no reason provided"}")
            "Ogrzewanie wyłączone. ${request.reason ?: ""}"
        } else {
            "Błąd: Nie udało się wyłączyć ogrzewania."
        }
    }

    @Serializable
    data class SendMessageRequest(
        val to: String,
        val message: String,
        val type: String = "INFORM"
    )

    private suspend fun sendMessageTool(request: SendMessageRequest): String {
        val messageType = when (request.type.uppercase()) {
            "REQUEST" -> MessageType.REQUEST
            "QUERY" -> MessageType.QUERY
            "RESPONSE" -> MessageType.RESPONSE
            else -> MessageType.INFORM
        }

        val messageRequest = AgentMessageRequest(
            from = agentId,
            to = request.to,
            type = messageType,
            content = request.message,
            context = null
        )

        val success = simulatorClient.sendMessage(messageRequest)
        return if (success) {
            println("📤 Message sent to ${request.to}: ${request.message}")
            "Wiadomość wysłana do ${request.to}."
        } else {
            "Błąd: Nie udało się wysłać wiadomości."
        }
    }

    private suspend fun processLLMResponse(
        response: String,
        state: EnvironmentState,
        currentHeating: Boolean
    ) {
        // Parsuj odpowiedź LLM - szukaj słów kluczowych
        val responseLower = response.lowercase()
        
        // Sprawdź czy LLM chce włączyć/wyłączyć ogrzewanie
        val shouldTurnOn = responseLower.contains("włącz") || 
                          responseLower.contains("turn on") ||
                          responseLower.contains("włączyć") ||
                          (responseLower.contains("potrzeb") && responseLower.contains("ogrzew"))
        
        val shouldTurnOff = responseLower.contains("wyłącz") ||
                            responseLower.contains("turn off") ||
                            responseLower.contains("wyłączyć") ||
                            (responseLower.contains("nie potrzeb") && responseLower.contains("ogrzew"))
        
        // Sprawdź czy LLM chce wysłać komunikat
        val shouldSendMessage = responseLower.contains("wyślij") ||
                               responseLower.contains("send") ||
                               responseLower.contains("poinformuj")
        
        // Wykonaj akcje na podstawie odpowiedzi LLM
        if (shouldTurnOn && !currentHeating) {
            turnOnHeatingTool(TurnOnHeatingRequest("LLM: $response"))
        } else if (shouldTurnOff && currentHeating) {
            turnOffHeatingTool(TurnOffHeatingRequest("LLM: $response"))
        }
        
        // Jeśli LLM sugeruje wysłanie komunikatu, spróbuj wyekstrahować odbiorcę i treść
        if (shouldSendMessage) {
            // Prosta ekstrakcja - w przyszłości można użyć bardziej zaawansowanego parsowania
            val toAgent = extractAgentName(response)
            if (toAgent != null) {
                val messageContent = extractMessageContent(response) ?: "Właśnie zmieniłem stan ogrzewania."
                sendMessageTool(SendMessageRequest(toAgent, messageContent))
            }
        }
    }

    private suspend fun processLLMMessageResponse(
        response: String,
        message: AgentMessage,
        state: EnvironmentState,
        currentHeating: Boolean
    ) {
        // Parsuj odpowiedź LLM na komunikat
        val responseLower = response.lowercase()
        
        // Sprawdź czy powinien zareagować
        val shouldReact = !responseLower.contains("nie") && 
                         !responseLower.contains("brak") &&
                         (responseLower.contains("włącz") ||
                          responseLower.contains("wyłącz") ||
                          responseLower.contains("zmień"))
        
        if (shouldReact) {
            // Sprawdź czy włączyć/wyłączyć ogrzewanie
            val shouldTurnOn = responseLower.contains("włącz") || responseLower.contains("turn on")
            val shouldTurnOff = responseLower.contains("wyłącz") || responseLower.contains("turn off")
            
            if (shouldTurnOn && !currentHeating) {
                turnOnHeatingTool(TurnOnHeatingRequest("Reakcja na komunikat: $response"))
            } else if (shouldTurnOff && currentHeating) {
                turnOffHeatingTool(TurnOffHeatingRequest("Reakcja na komunikat: $response"))
            }
            
            // Można też odpowiedzieć na komunikat
            val shouldRespond = responseLower.contains("odpowiedz") || responseLower.contains("reply")
            if (shouldRespond) {
                val replyContent = extractMessageContent(response) ?: "Zrozumiałem i zareagowałem."
                sendMessageTool(SendMessageRequest(message.from, replyContent, "RESPONSE"))
            }
        }
    }

    private fun extractAgentName(response: String): String? {
        // Prosta ekstrakcja nazwy agenta z odpowiedzi
        val patterns = listOf(
            "WindowBlindsAgent", "BlindsAgent", "blinds",
            "LightAgent", "light",
            "PrinterAgent", "printer"
        )
        
        for (pattern in patterns) {
            if (response.contains(pattern, ignoreCase = true)) {
                return when {
                    pattern.contains("Blinds", ignoreCase = true) -> "blinds_agent"
                    pattern.contains("Light", ignoreCase = true) -> "light_agent"
                    pattern.contains("Printer", ignoreCase = true) -> "printer_agent"
                    else -> null
                }
            }
        }
        return null
    }

    private fun extractMessageContent(response: String): String? {
        // Prosta ekstrakcja treści komunikatu
        // Szukaj tekstu w cudzysłowach lub po dwukropku
        val quotePattern = """"([^"]+)"""".toRegex()
        val match = quotePattern.find(response)
        if (match != null) {
            return match.groupValues[1]
        }
        
        // Jeśli nie ma cudzysłowów, weź tekst po ":" lub "komunikat:"
        val colonPattern = "(?:komunikat|message|treść)[:：]\\s*(.+)".toRegex(RegexOption.IGNORE_CASE)
        val colonMatch = colonPattern.find(response)
        if (colonMatch != null) {
            return colonMatch.groupValues[1].trim()
        }
        
        return null
    }
}

