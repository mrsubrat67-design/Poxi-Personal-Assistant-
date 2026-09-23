package com.example.poxi.model

import com.example.BuildConfig

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    STOPPING,
    ERROR
}

data class PoxiUiState(
    val sessionState: VoiceSessionState = VoiceSessionState.IDLE,
    val isListening: Boolean = false,
    val isVoiceSessionActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessing: Boolean = false,
    val statusMessage: String = "Tap mic to start",
    val messages: List<ChatMessage> = emptyList(),
    val apiKey: String = BuildConfig.GEMINI_API_KEY,
    val currentLanguage: String = "English",
    val audioAmplitude: Float = 0f,
    val lastExecutedAction: ToolActionInfo? = null,
    val pendingContactDisambiguation: List<ContactItem>? = null,
    val hasMicrophonePermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val hasPhoneCallPermission: Boolean = false,
    val showPermissionDeniedDialog: Boolean = false,
    val permissionDialogTitle: String = "Microphone Permission Required",
    val permissionDialogMessage: String = "Poxi needs microphone access to listen to your voice commands.",
    val isPermissionPermanentlyDenied: Boolean = false
)
