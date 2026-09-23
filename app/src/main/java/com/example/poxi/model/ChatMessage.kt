package com.example.poxi.model

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ToolActionInfo(
    val functionName: String,
    val arguments: Map<String, Any?>,
    val success: Boolean,
    val summary: String,
    val detail: String? = null,
    val appOrTarget: String? = null
)

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val typeLabel: String = "Mobile"
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolAction: ToolActionInfo? = null,
    val detectedLanguage: String? = null,
    val audioBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatMessage
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
