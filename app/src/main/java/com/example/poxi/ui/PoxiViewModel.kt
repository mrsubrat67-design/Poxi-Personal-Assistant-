package com.example.poxi.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.poxi.audio.SpeechInputManager
import com.example.poxi.audio.VoiceOutputManager
import com.example.poxi.bridge.AndroidActionBridge
import com.example.poxi.bridge.BridgeResult
import com.example.poxi.gemini.GeminiService
import com.example.poxi.gemini.GeminiTurnResult
import com.example.poxi.model.ChatMessage
import com.example.poxi.model.ContactItem
import com.example.poxi.model.MessageRole
import com.example.poxi.model.ToolActionInfo
import com.example.poxi.service.PoxiVoiceService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PoxiUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessing: Boolean = false,
    val isVoiceSessionActive: Boolean = false,
    val audioAmplitude: Float = 0f,
    val currentSpokenText: String? = null,
    val lastExecutedAction: ToolActionInfo? = null,
    val pendingContactDisambiguation: List<ContactItem>? = null,
    val currentLanguage: String = "English / Hinglish / हिंदी",
    val hasMicrophonePermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val statusMessage: String = "Tap the microphone to speak with Poxi",
    val isNativeBridgeReady: Boolean = true,
    val apiKey: String = ""
)

class PoxiViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val actionBridge = AndroidActionBridge(context)
    val voiceOutputManager = VoiceOutputManager(context)
    val speechInputManager = SpeechInputManager(context)
    val geminiService = GeminiService(actionBridge)

    private val _uiState = MutableStateFlow(PoxiUiState())
    val uiState: StateFlow<PoxiUiState> = _uiState.asStateFlow()

    init {
        // Resolve API key from BuildConfig or saved prefs
        val initialKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
        val safeKey = if (initialKey == "MY_GEMINI_API_KEY") "" else initialKey

        _uiState.update {
            it.copy(
                apiKey = safeKey,
                hasMicrophonePermission = checkPermission(Manifest.permission.RECORD_AUDIO),
                hasContactsPermission = checkPermission(Manifest.permission.READ_CONTACTS)
            )
        }

        // Setup Audio callbacks
        voiceOutputManager.onSpeakingStarted = {
            _uiState.update { it.copy(isSpeaking = true, statusMessage = "Poxi is speaking...") }
        }

        voiceOutputManager.onSpeakingFinished = {
            _uiState.update { it.copy(isSpeaking = false, statusMessage = "Listening for your reply...") }
            // Continuous session: auto-listen after Poxi finishes speaking
            if (_uiState.value.isVoiceSessionActive) {
                viewModelScope.launch {
                    delay(350)
                    if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking && !_uiState.value.isProcessing) {
                        speechInputManager.startListening()
                    }
                }
            }
        }

        voiceOutputManager.audioPlayer.onAmplitudeUpdated = { amp ->
            _uiState.update { it.copy(audioAmplitude = amp) }
        }

        // Setup Speech Recognition callbacks
        speechInputManager.onListeningStarted = {
            _uiState.update {
                it.copy(
                    isListening = true,
                    statusMessage = "Listening... speak now"
                )
            }
        }

        speechInputManager.onListeningFinished = {
            _uiState.update { it.copy(isListening = false) }
        }

        speechInputManager.onSpeechTimeout = {
            // Keep session active on pause/silence: re-arm recognizer
            if (_uiState.value.isVoiceSessionActive) {
                viewModelScope.launch {
                    delay(250)
                    if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking && !_uiState.value.isProcessing) {
                        speechInputManager.startListening()
                    }
                }
            }
        }

        speechInputManager.onRmsChanged = { rms ->
            if (_uiState.value.isListening) {
                _uiState.update { it.copy(audioAmplitude = rms) }
            }
        }

        speechInputManager.onSpeechResult = { recognizedText ->
            processUserInput(recognizedText)
        }

        speechInputManager.onError = { errorMsg ->
            _uiState.update {
                it.copy(
                    isListening = false,
                    statusMessage = errorMsg
                )
            }
        }

        // Notification stop button trigger
        PoxiVoiceService.onStopActionTriggered = {
            stopVoiceSession()
        }

        // Add welcome message
        val welcomeMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            text = "Namaste! I'm Poxi, your voice AI assistant. I can speak English, Hindi, Hinglish, Marathi, and more. Try saying \"WhatsApp kholo\", \"Call Mom\", or \"Open YouTube\"!",
            detectedLanguage = "Hinglish"
        )
        _uiState.update { it.copy(messages = listOf(welcomeMsg)) }
    }

    fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionsResult(micGranted: Boolean, contactsGranted: Boolean) {
        _uiState.update {
            it.copy(
                hasMicrophonePermission = micGranted,
                hasContactsPermission = contactsGranted,
                statusMessage = if (micGranted) "Microphone ready! Tap mic to speak" else "Microphone permission is required to talk to Poxi"
            )
        }
    }

    fun toggleListening() {
        if (_uiState.value.isSpeaking) {
            // Interrupt Poxi while speaking
            interruptSpeaking()
            return
        }

        if (_uiState.value.isVoiceSessionActive) {
            stopVoiceSession()
        } else {
            if (!checkPermission(Manifest.permission.RECORD_AUDIO)) {
                _uiState.update {
                    it.copy(
                        hasMicrophonePermission = false,
                        statusMessage = "Microphone permission required — please allow access"
                    )
                }
                return
            }
            startVoiceSession()
        }
    }

    /**
     * Starts continuous voice session with Android Foreground Service.
     */
    fun startVoiceSession() {
        if (!checkPermission(Manifest.permission.RECORD_AUDIO)) {
            _uiState.update {
                it.copy(
                    hasMicrophonePermission = false,
                    statusMessage = "Microphone permission required"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isVoiceSessionActive = true,
                statusMessage = "Listening... speak now"
            )
        }
        PoxiVoiceService.start(context)
        speechInputManager.startListening()
    }

    /**
     * Stops continuous voice session, stops speech recognizer, halts audio playback,
     * and terminates the Foreground Service.
     */
    fun stopVoiceSession() {
        _uiState.update {
            it.copy(
                isVoiceSessionActive = false,
                isListening = false,
                isSpeaking = false,
                audioAmplitude = 0f,
                statusMessage = "Voice session stopped • Tap mic to start"
            )
        }
        speechInputManager.stopListening()
        voiceOutputManager.stop()
        PoxiVoiceService.stop(context)
    }

    /**
     * Immediately silences Poxi when user interrupts, and immediately re-arms the mic if session is active.
     */
    fun interruptSpeaking() {
        voiceOutputManager.stop()
        _uiState.update {
            it.copy(
                isSpeaking = false,
                statusMessage = "Interrupted — Listening for your command..."
            )
        }
        if (_uiState.value.isVoiceSessionActive) {
            viewModelScope.launch {
                delay(200)
                if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking) {
                    speechInputManager.startListening()
                }
            }
        }
    }

    fun processUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        // If currently speaking, stop immediately
        voiceOutputManager.stop()

        // Append user message
        val userMsg = ChatMessage(
            role = MessageRole.USER,
            text = trimmed
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMsg,
                isProcessing = true,
                statusMessage = "Poxi is thinking..."
            )
        }

        viewModelScope.launch {
            val keyToUse = _uiState.value.apiKey
            val result = geminiService.processUserTurn(trimmed, keyToUse)

            when (result) {
                is GeminiTurnResult.Success -> {
                    val assistantMsg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = result.spokenText,
                        audioBytes = result.audioBytes,
                        toolAction = result.toolAction,
                        detectedLanguage = result.detectedLanguage
                    )

                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + assistantMsg,
                            isProcessing = false,
                            lastExecutedAction = result.toolAction,
                            pendingContactDisambiguation = result.pendingContacts,
                            currentLanguage = result.detectedLanguage ?: state.currentLanguage,
                            statusMessage = if (result.toolAction != null) "Action executed: ${result.toolAction.summary}" else "Poxi responded"
                        )
                    }

                    // Speak out Poxi's response
                    voiceOutputManager.speak(
                        text = result.spokenText,
                        audioBytes = result.audioBytes,
                        languageHint = result.detectedLanguage
                    )
                }
                is GeminiTurnResult.Error -> {
                    val errorMsg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = "I encountered an error: ${result.message}"
                    )
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + errorMsg,
                            isProcessing = false,
                            statusMessage = "Error processing request"
                        )
                    }
                }
            }
        }
    }

    fun selectDisambiguatedContact(contact: ContactItem) {
        _uiState.update { it.copy(pendingContactDisambiguation = null) }
        val prompt = "Calling ${contact.name}"
        actionBridge.executeMakeCall(contact.phoneNumber)
        val msg = ChatMessage(
            role = MessageRole.ASSISTANT,
            text = "Calling ${contact.name} at ${contact.phoneNumber}.",
            toolAction = ToolActionInfo(
                functionName = "callContact",
                arguments = mapOf("contactName" to contact.name, "phoneNumber" to contact.phoneNumber),
                success = true,
                summary = "Calling ${contact.name} (${contact.phoneNumber})",
                detail = contact.phoneNumber,
                appOrTarget = contact.name
            )
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
        voiceOutputManager.speak("Calling ${contact.name}")
    }

    fun dismissDisambiguation() {
        _uiState.update { it.copy(pendingContactDisambiguation = null) }
    }

    fun updateApiKey(newKey: String) {
        _uiState.update { it.copy(apiKey = newKey.trim()) }
    }

    fun clearMessages() {
        geminiService.resetConversation()
        _uiState.update { it.copy(messages = emptyList(), lastExecutedAction = null, pendingContactDisambiguation = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceSession()
        speechInputManager.destroy()
        voiceOutputManager.shutdown()
    }
}
