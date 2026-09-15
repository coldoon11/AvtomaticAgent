package com.example.autonomousai

data class ChatMessage(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

data class ScreenSnapshot(
    val packageName: String = "",
    val text: String = "",
    val updatedAtMillis: Long = 0L,
)
