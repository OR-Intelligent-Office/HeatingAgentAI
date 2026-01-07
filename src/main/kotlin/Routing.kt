package com.pawlowski

import ai.koog.ktor.aiAgent
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import io.ktor.resources.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class LLMRequest(
    val prompt: String,
    val systemPrompt: String
)

@Serializable
data class LLMResponse(
    val response: String
)

fun Application.configureRouting() {
    install(Resources)
    routing {
        get("/") {
            call.respondText("HeatingAgent AI - Internal API")
        }
        
        // Wewnętrzny endpoint do wywoływania LLM przez agenta (używa Koog)
        post("/internal/llm/decision") {
            try {
                val request = call.receive<LLMRequest>()
                // Połącz system prompt z promptem użytkownika
                val fullPrompt = "${request.systemPrompt}\n\n${request.prompt}"
                
                println("🔵 Wywołuję Ollama LLM przez Koog (prompt length: ${fullPrompt.length} chars)")
                
                // Użyj Koog aiAgent - Koog automatycznie użyje modelu Ollama skonfigurowanego w Frameworks.kt
                // aiAgent wymaga parametru model - używamy OpenAIModels jako placeholder (Koog wybierze Ollama z konfiguracji)
                // W rzeczywistości Koog powinien automatycznie wybrać Ollama gdy jest skonfigurowany
                // TODO: Znaleźć właściwy sposób na określenie modelu Ollama w Koog
                val responseText = aiAgent(input = fullPrompt, model = ollamaModel)
                
                println("✅ Ollama odpowiedział przez Koog (length: ${responseText.length} chars)")
                
                call.respond(LLMResponse(responseText))
            } catch (e: Exception) {
                println("❌ Błąd w endpoincie LLM: ${e.javaClass.simpleName} - ${e.message}")
                e.printStackTrace()
                call.respond(io.ktor.http.HttpStatusCode.InternalServerError, 
                    mapOf("error" to (e.message ?: "Unknown error")))
            }
        }
        
        get<Articles> { article ->
            // Get all articles ...
            call.respond("List of articles sorted starting from ${article.sort}")
        }
    }
}

@Serializable
@Resource("/articles")
class Articles(val sort: String? = "new")
