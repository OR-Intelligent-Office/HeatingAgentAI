package com.pawlowski.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import com.pawlowski.client.SimulatorClient
import com.pawlowski.models.AgentMessage
import com.pawlowski.models.EnvironmentState
import com.pawlowski.ollamaModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
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

    private val systemPrompt = """
Jesteś agentem ogrzewania w inteligentnym biurze. Twoim zadaniem jest zarządzanie ogrzewaniem w budynku.

WAŻNE: Musisz używać dostępnych narzędzi (Tools) do wykonywania akcji. Twoja odpowiedź tekstowa nie ma znaczenia - ważne jest tylko to, jakie narzędzia wywołasz.

Dostępne narzędzia (Tools):
- turn_on_heating(roomId, reason) - włącz ogrzewanie dla konkretnego pokoju (podaj ID pokoju)
- turn_off_heating(roomId, reason) - wyłącz ogrzewanie dla konkretnego pokoju (podaj ID pokoju)
- send_message(to_agent, message, type) - wyślij komunikat w języku naturalnym do innego agenta

WAŻNE - Jak działa system ogrzewania:
- Gdy ogrzewanie jest WŁĄCZONE dla pokoju, system automatycznie dąży do temperatury docelowej 22°C
- Jeśli temperatura jest PONIŻEJ 22°C → system ogrzeje pokój do 22°C
- Jeśli temperatura jest POWYŻEJ 22°C → system schłodzi pokój do 22°C (klimatyzacja/chłodzenie)
- Gdy ogrzewanie jest WYŁĄCZONE → temperatura zbliża się do temperatury zewnętrznej (nie ma kontroli temperatury)

Zasady działania:
1. Włącz ogrzewanie dla konkretnego pokoju gdy temperatura < 21°C i są osoby w tym pokoju (system ogrzeje do 22°C)
2. Włącz ogrzewanie dla pokoju 15 minut przed zaplanowanym spotkaniem w tym pokoju (używaj czasu SYMULACJI z stanu środowiska, nie czasu rzeczywistego!)
3. Włącz ogrzewanie dla pokoju gdy temperatura > 24°C (system schłodzi do 22°C poprzez włączenie ogrzewania)
4. Wyłącz ogrzewanie dla pokoju gdy temperatura jest bliska 22°C i nie ma potrzeby utrzymywania temperatury
5. Wyłącz ogrzewanie dla pokoju gdy temperatura >= 18°C i nie ma osób (oszczędność energii)
6. Utrzymuj minimum 17°C w pokoju gdy nie ma osób (zapobieganie zamarzaniu)
7. Oszczędzaj energię - wyłącz ogrzewanie gdy nie jest potrzebne, ale pamiętaj że włączenie ogrzewania pozwala kontrolować temperaturę (ogrzewanie i chłodzenie)

WAŻNE - Czas:
- Zawsze używaj czasu SYMULACJI (simulationTime) z aktualnego stanu środowiska do porównywania z czasami spotkań
- NIE używaj czasu rzeczywistego - porównuj czasy spotkań z czasem symulacji!

Dostępni agenci do komunikacji:
- WindowBlindsAgent: kontroluje rolety okienne (ochrona przed upałem, światło dzienne)
- LightAgent: kontroluje światła (włącza/wyłącza)
- PrinterAgent: kontroluje drukarki (włącza/wyłącza, zarządza zasobami)

PAMIĘTAJ: Zawsze używaj narzędzi (Tools) do wykonywania akcji. Nie odpowiadaj tekstowo - wywołuj narzędzia!
""".trimIndent()

    private val messageProcessingPrompt = """
Jesteś agentem ogrzewania. Otrzymałeś komunikat w języku naturalnym od innego agenta.

WAŻNE: Musisz używać dostępnych narzędzi (Tools) do wykonywania akcji. Twoja odpowiedź tekstowa nie ma znaczenia - ważne jest tylko to, jakie narzędzia wywołasz.

Dostępne narzędzia (Tools):
- turn_on_heating(roomId, reason) - włącz ogrzewanie dla konkretnego pokoju
- turn_off_heating(roomId, reason) - wyłącz ogrzewanie dla konkretnego pokoju
- send_message(to_agent, message, type) - odpowiedz innemu agentowi

Przeanalizuj komunikat i zdecyduj czy powinieneś zareagować. Jeśli tak, WYWOŁAJ ODPOWIEDNIE NARZĘDZIA - nie odpowiadaj tekstowo!
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
        
        if (state.powerOutage) {
            println("⚠️ Power outage - heating unavailable")
            return
        }

        // Loguj temperatury wszystkich pokoi dla lepszej analizy decyzji
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val currentTime = try {
            LocalDateTime.parse(state.simulationTime, formatter)
        } catch (e: Exception) {
            println("⚠️ Błąd parsowania czasu symulacji: ${state.simulationTime}")
            null
        }
        
        println("🔄 Cykl decyzyjny - czas symulacji: ${state.simulationTime} | temp zew: ${String.format("%.1f", state.externalTemperature)}°C")
        coroutineScope {
            state.rooms.forEach { room ->
                val roomHeatingState = async { simulatorClient.getRoomHeatingState(room.id) ?: false }
                val heatingState = roomHeatingState.await()
                
                // Znajdź 2 najbliższe spotkania (włącznie z aktualnym)
                val upcomingMeetings = if (currentTime != null && room.scheduledMeetings.isNotEmpty()) {
                    room.scheduledMeetings
                        .mapNotNull { meeting ->
                            try {
                                val startTime = LocalDateTime.parse(meeting.startTime, formatter)
                                val endTime = LocalDateTime.parse(meeting.endTime, formatter)
                                // Weź spotkania które jeszcze się nie skończyły
                                if (endTime.isAfter(currentTime)) {
                                    Triple(meeting, startTime, endTime)
                                } else null
                            } catch (e: Exception) {
                                null
                            }
                        }
                        .sortedBy { it.second } // Sortuj po startTime
                        .take(2) // Weź 2 najbliższe
                        .map { 
                            val (meeting, start, end) = it
                            val timeInfo = when {
                                // Spotkanie trwa
                                start.isBefore(currentTime) && end.isAfter(currentTime) -> {
                                    val minutesLeft = Duration.between(currentTime, end).toMinutes()
                                    "TRWA (zostało ${minutesLeft} min)"
                                }
                                // Spotkanie nadchodzące
                                start.isAfter(currentTime) -> {
                                    val minutesUntil = Duration.between(currentTime, start).toMinutes()
                                    "za ${minutesUntil} min"
                                }
                                else -> "ZAKOŃCZONE"
                            }
                            "${meeting.title} [$timeInfo]"
                        }
                } else {
                    emptyList()
                }
                
                val meetingsInfo = if (upcomingMeetings.isNotEmpty()) {
                    " | Spotkania: ${upcomingMeetings.joinToString(", ")}"
                } else {
                    " | Spotkania: brak"
                }
                
                println("   📍 ${room.name}: ${String.format("%.1f", room.temperatureSensor.temperature)}°C | Ogrzewanie: ${if (heatingState) "ON" else "OFF"} | Osoby: ${room.peopleCount}$meetingsInfo")
            }
        }
        
        // Buduj prompt z aktualnym stanem (per-room heating state)
        val prompt = buildDecisionPrompt(state)

        // Wywołaj LLM przez AIAgent - Koog automatycznie obsłuży tool calls
        // AIAgent jest single-use, więc tworzymy nowy dla każdego wywołania
        try {
            println("🔵 Wywołuję LLM przez AIAgent (prompt length: ${prompt.length} chars)")
            val agent = createAIAgent()
            val response = agent.run(prompt)
            println("✅ LLM odpowiedział (length: ${response.length} chars)")
            // Tools są wywoływane automatycznie przez Koog - nie trzeba parsować odpowiedzi
        } catch (e: Exception) {
            println("❌ Błąd w cyklu decyzyjnym: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun buildDecisionPrompt(state: EnvironmentState): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val currentTime = try {
            LocalDateTime.parse(state.simulationTime, formatter)
        } catch (e: Exception) {
            null
        }
        
        val roomsInfo = coroutineScope {
            state.rooms.map { room ->
                async {
                    val roomHeatingState = simulatorClient.getRoomHeatingState(room.id) ?: false
                    
                    // Formatuj spotkania z czasem do rozpoczęcia/końca
                    val meetingsText = if (room.scheduledMeetings.isNotEmpty() && currentTime != null) {
                        room.scheduledMeetings
                            .mapNotNull { meeting ->
                                try {
                                    val startTime = LocalDateTime.parse(meeting.startTime, formatter)
                                    val endTime = LocalDateTime.parse(meeting.endTime, formatter)
                                    if (endTime.isAfter(currentTime)) {
                                        Triple(meeting, startTime, endTime)
                                    } else null
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            .sortedBy { it.second } // Sortuj po startTime
                            .take(2) // Weź 2 najbliższe
                            .mapNotNull { (meeting, start, end) ->
                                val timeInfo = when {
                                    start.isBefore(currentTime) && end.isAfter(currentTime) -> {
                                        val minutesLeft = Duration.between(currentTime, end).toMinutes()
                                        "TRWA (zostało ${minutesLeft} min)"
                                    }
                                    start.isAfter(currentTime) -> {
                                        val minutesUntil = Duration.between(currentTime, start).toMinutes()
                                        "za ${minutesUntil} min"
                                    }
                                    else -> null
                                }
                                if (timeInfo != null) {
                                    "${meeting.title} [$timeInfo]"
                                } else null
                            }
                            .joinToString(", ")
                            .ifEmpty { "brak" }
                    } else if (room.scheduledMeetings.isNotEmpty()) {
                        room.scheduledMeetings.take(2).joinToString(", ") { 
                            "${it.title} (${it.startTime} - ${it.endTime})"
                        }
                    } else {
                        "brak"
                    }
                    
                    """
                    Pokój ${room.name} (${room.id}):
                    - Temperatura: ${room.temperatureSensor.temperature}°C
                    - Ogrzewanie: ${if (roomHeatingState) "WŁĄCZONE (system dąży do 22°C - może ogrzewać lub chłodzić)" else "WYŁĄCZONE"}
                    - Osoby: ${room.peopleCount}
                    - Spotkania: $meetingsText
                    """.trimIndent()
                }
            }.awaitAll()
        }.joinToString("\n")

        return """
Aktualny stan środowiska:
- CZAS SYMULACJI (używaj tego do porównywania z czasami spotkań!): ${state.simulationTime}
- Temperatura zewnętrzna: ${state.externalTemperature}°C
- Awaria zasilania: ${if (state.powerOutage) "TAK" else "NIE"}

Pokoje:
$roomsInfo

Przeanalizuj sytuację dla każdego pokoju i zdecyduj czy powinieneś:
1. Włączyć ogrzewanie (gdy temperatura < 21°C lub > 24°C)
2. Wyłączyć ogrzewanie (gdy temperatura jest w zakresie 21-23°C i nie ma potrzeby kontroli)
3. Wysłać komunikat do innego agenta
4. Nic nie robić (utrzymać obecny stan)

WAŻNE - Do oceny czy spotkanie jest "15 minut przed", porównuj czas startTime spotkania z CZASEM SYMULACJI powyżej. NIE używaj czasu rzeczywistego!

PAMIĘTAJ: Włączenie ogrzewania pozwala systemowi kontrolować temperaturę - jeśli temperatura > 22°C, system automatycznie schłodzi do 22°C. Jeśli temperatura < 22°C, system automatycznie ogrzeje do 22°C.

Używaj narzędzi (Tools) do wykonywania akcji - nie odpowiadaj tekstowo!
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

