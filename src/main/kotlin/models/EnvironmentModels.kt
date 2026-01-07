package com.pawlowski.models

import kotlinx.serialization.Serializable

@Serializable
data class EnvironmentState(
    val simulationTime: String,
    val rooms: List<Room>,
    val externalTemperature: Double,
    val timeSpeedMultiplier: Double = 1.0,
    val powerOutage: Boolean = false,
    val daylightIntensity: Double = 1.0,
    val isHeating: Boolean = false
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    val lights: List<LightDevice>,
    val printer: PrinterDevice?,
    val motionSensor: MotionSensor,
    val temperatureSensor: TemperatureSensor,
    val blinds: BlindsDevice?,
    val peopleCount: Int = 0,
    val scheduledMeetings: List<Meeting> = emptyList()
)

@Serializable
data class LightDevice(
    val id: String,
    val roomId: String,
    val state: String,
    val brightness: Int = 100
)

@Serializable
data class PrinterDevice(
    val id: String,
    val roomId: String,
    val state: String,
    val tonerLevel: Int = 100,
    val paperLevel: Int = 100
)

@Serializable
data class MotionSensor(
    val id: String,
    val roomId: String,
    val motionDetected: Boolean,
    val lastMotionTime: String? = null
)

@Serializable
data class TemperatureSensor(
    val id: String,
    val roomId: String,
    val temperature: Double
)

@Serializable
data class BlindsDevice(
    val id: String,
    val roomId: String,
    val state: String
)

@Serializable
data class Meeting(
    val startTime: String,
    val endTime: String,
    val title: String = "Spotkanie"
)

@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
    val alertId: String? = null
)

@Serializable
enum class MessageType {
    REQUEST,
    INFORM,
    QUERY,
    RESPONSE
}

@Serializable
data class AgentMessage(
    val id: String,
    val from: String,
    val to: String,
    val type: MessageType,
    val content: String,
    val timestamp: String,
    val context: Map<String, String>? = null
)

@Serializable
data class AgentMessageRequest(
    val from: String,
    val to: String,
    val type: MessageType,
    val content: String,
    val context: Map<String, String>? = null
)

