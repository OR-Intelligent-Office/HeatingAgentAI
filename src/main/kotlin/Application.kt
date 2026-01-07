package com.pawlowski

import com.pawlowski.agent.HeatingAgent
import com.pawlowski.client.SimulatorClient
import io.ktor.server.application.*
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureFrameworks()
    configureRouting()
    
    // Uruchom HeatingAgent AI
    val simulatorUrl = environment.config.propertyOrNull("heating.agent.simulator.url")?.getString() 
        ?: "http://localhost:8080"
    val simulatorClient = SimulatorClient(simulatorUrl)
    val heatingAgent = HeatingAgent(simulatorClient)
    
    // Uruchom agenta w tle
    monitor.subscribe(ApplicationStarted) {
        CoroutineScope(Dispatchers.Default).launch {
            heatingAgent.start()
        }
    }
    
    monitor.subscribe(ApplicationStopped) {
        heatingAgent.stop()
        simulatorClient.close()
    }
}
