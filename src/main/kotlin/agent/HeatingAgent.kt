package com.pawlowski.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import com.pawlowski.client.SimulatorClient
import com.pawlowski.models.AgentMessage
import com.pawlowski.models.EnvironmentState
import com.pawlowski.ollamaModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    
    // Utworz Tools
    private val heatingTools = HeatingTools(simulatorClient, agentId)
    
    // Utworz ToolRegistry z Tools
    private val toolRegistry = ToolRegistry {
        tools(heatingTools)
    }
    
    // Utworz prompt executor dla Ollama
    private val promptExecutor = simpleOllamaAIExecutor(baseUrl = "http://localhost:11434")
    
    // Funkcja do tworzenia nowego AIAgent (agent jest single-use, więc tworzymy nowy dla każdego wywołania)
    private fun createAIAgent(): AIAgent<String, String> {
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = ollamaModel,
            systemPrompt = systemPrompt,
            toolRegistry = toolRegistry
        )
    }

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

        // Wywołaj LLM przez AIAgent - Koog automatycznie obsłuży tool calls
        // AIAgent jest single-use, więc tworzymy nowy dla każdego wywołania
        try {
            println("🔵 Wywołuję LLM przez AIAgent (prompt length: ${prompt.length} chars)")
            val agent = createAIAgent()
            val response = agent.run(prompt)
            println("✅ LLM odpowiedział (length: ${response.length} chars)")
            if (response.length < 200) {
                println("   Treść: $response")
            } else {
                println("   Treść (pierwsze 200 znaków): ${response.take(200)}...")
            }
            // Tools są wywoływane automatycznie przez Koog - nie trzeba parsować odpowiedzi
        } catch (e: Exception) {
            println("❌ Błąd w cyklu decyzyjnym: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
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

        // Wywołaj LLM przez AIAgent - Koog automatycznie obsłuży tool calls
        // AIAgent jest single-use, więc tworzymy nowy dla każdego wywołania
        try {
            println("🔵 Wywołuję LLM przez AIAgent dla wiadomości (prompt length: ${prompt.length} chars)")
            val agent = createAIAgent()
            val response = agent.run(prompt)
            println("✅ LLM odpowiedział na wiadomość (length: ${response.length} chars)")
            // Tools są wywoływane automatycznie przez Koog - nie trzeba parsować odpowiedzi
        } catch (e: Exception) {
            println("❌ Error calling LLM for message: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
    }

}

