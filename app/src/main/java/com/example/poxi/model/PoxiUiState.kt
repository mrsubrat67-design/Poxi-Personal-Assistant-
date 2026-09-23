package com.example.poxi.model

data class PoxiUiState(
    val isListening: Boolean = false,
    val isVoiceSessionActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessing: Boolean = false,
    val statusMessage: String = "Tap mic to start",
    val messages: List<ChatMessage> = emptyList(),
    val apiKey: String = "",
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
