package com.pawlowski

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

private const val OLLAMA_MODEL_NAME = "llama3.1:8b"

val ollamaModel = LLModel(
    provider = LLMProvider.Ollama,
    id = OLLAMA_MODEL_NAME,
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Tools
    ),
    contextLength = 40_960,
)