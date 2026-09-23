package com.example.poxi.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.poxi.audio.SpeechInputManager
import com.example.poxi.audio.VoiceLogger
import com.example.poxi.audio.VoiceOutputManager
import com.example.poxi.bridge.AndroidActionBridge
import com.example.poxi.gemini.GeminiService
import com.example.poxi.gemini.GeminiTurnResult
import com.example.poxi.model.ChatMessage
import com.example.poxi.model.ContactItem
import com.example.poxi.model.MessageRole
import com.example.poxi.model.PoxiUiState
import com.example.poxi.model.ToolActionInfo
import com.example.poxi.model.VoiceSessionState
import com.example.poxi.permission.PermissionValidationLayer
import com.example.poxi.service.PoxiVoiceService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PoxiViewModel @JvmOverloads constructor(
    application: Application,
    val actionBridge: AndroidActionBridge = AndroidActionBridge(application),
    val geminiService: GeminiService = GeminiService(actionBridge),
    val speechInputManager: SpeechInputManager = SpeechInputManager(application),
    val voiceOutputManager: VoiceOutputManager = VoiceOutputManager(application)
) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(PoxiUiState())
    val uiState: StateFlow<PoxiUiState> = _uiState.asStateFlow()

    private var autoRestartJob: kotlinx.coroutines.Job? = null

    init {
        // Initial permission check using strict validation layer
        val hasMicPermission = PermissionValidationLayer.hasRecordAudioPermission(context)
        val hasContacts = PermissionValidationLayer.hasContactsPermission(context)
        val hasCallPhone = PermissionValidationLayer.hasCallPhonePermission(context)

        _uiState.update {
            it.copy(
                hasMicrophonePermission = hasMicPermission,
                hasContactsPermission = hasContacts,
                hasPhoneCallPermission = hasCallPhone,
                isTtsReady = voiceOutputManager.isTtsReady,
                speechRate = voiceOutputManager.speechRate,
                speechPitch = voiceOutputManager.speechPitch
            )
        }

        // Setup Audio Playback callbacks
        voiceOutputManager.onSpeakingStarted = {
            autoRestartJob?.cancel()
            _uiState.update {
                it.copy(
                    sessionState = VoiceSessionState.SPEAKING,
                    isSpeaking = true,
                    isListening = false,
                    isProcessing = false,
                    statusMessage = "Poxi is speaking..."
                )
            }
            // Pause speech recognition while speaking to prevent self-triggering
            speechInputManager.pauseListening()
        }

        voiceOutputManager.onSpeakingFinished = {
            _uiState.update {
                it.copy(
                    sessionState = if (it.isVoiceSessionActive) VoiceSessionState.LISTENING else VoiceSessionState.IDLE,
                    isSpeaking = false,
                    isListening = it.isVoiceSessionActive,
                    statusMessage = if (it.isVoiceSessionActive) "Listening for your reply..." else "Ready"
                )
            }
            // Continuous session: resume listening seamlessly after Poxi finishes speaking
            if (_uiState.value.isVoiceSessionActive && !_uiState.value.isProcessing) {
                autoRestartJob?.cancel()
                autoRestartJob = viewModelScope.launch {
                    delay(100)
                    if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking && !_uiState.value.isProcessing) {
                        _uiState.update { it.copy(sessionState = VoiceSessionState.LISTENING, isListening = true) }
                        speechInputManager.resumeListening()
                    }
                }
            }
        }

        voiceOutputManager.onAmplitudeUpdated = { amp ->
            _uiState.update { it.copy(audioAmplitude = amp) }
        }

        voiceOutputManager.onTtsReadyChanged = { ready ->
            _uiState.update { it.copy(isTtsReady = ready) }
        }

        // Setup Speech Recognition callbacks
        speechInputManager.onListeningStarted = {
            _uiState.update {
                it.copy(
                    sessionState = VoiceSessionState.LISTENING,
                    isListening = true,
                    isSpeaking = false,
                    isProcessing = false,
                    statusMessage = "Listening... speak anytime"
                )
            }
        }

        speechInputManager.onListeningFinished = {
            // Only update UI if voice session is actually inactive
            if (!_uiState.value.isVoiceSessionActive) {
                _uiState.update { it.copy(sessionState = VoiceSessionState.IDLE, isListening = false) }
            }
        }

        speechInputManager.onSpeechTimeout = {
            // Continuous session: silence handled internally without toggling microphone off in UI
            if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking && !_uiState.value.isProcessing) {
                _uiState.update {
                    if (it.isVoiceSessionActive) it.copy(sessionState = VoiceSessionState.LISTENING, isListening = true) else it
                }
            }
        }

        speechInputManager.onRmsChanged = { rms ->
            if (_uiState.value.isListening && !_uiState.value.isSpeaking) {
                _uiState.update { it.copy(audioAmplitude = rms) }
            }
        }

        speechInputManager.onSpeechResult = { recognizedText ->
            processUserInput(recognizedText)
        }

        speechInputManager.onError = { errorMsg ->
            if (errorMsg.contains("permission", ignoreCase = true)) {
                _uiState.update {
                    it.copy(
                        sessionState = VoiceSessionState.ERROR,
                        isVoiceSessionActive = false,
                        isListening = false,
                        statusMessage = errorMsg
                    )
                }
            } else {
                _uiState.update {
                    it.copy(statusMessage = errorMsg)
                }
            }
        }

        // Notification stop button trigger from Foreground Service
        PoxiVoiceService.onStopActionTriggered = {
            stopVoiceSession()
        }

        // Welcome message
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

    fun updatePermissionsState() {
        _uiState.update {
            it.copy(
                hasMicrophonePermission = PermissionValidationLayer.hasRecordAudioPermission(context),
                hasContactsPermission = PermissionValidationLayer.hasContactsPermission(context),
                hasPhoneCallPermission = PermissionValidationLayer.hasCallPhonePermission(context)
            )
        }
    }

    fun onPermissionsResult(micGranted: Boolean, contactsGranted: Boolean, permanentlyDenied: Boolean = false) {
        _uiState.update {
            it.copy(
                hasMicrophonePermission = micGranted,
                hasContactsPermission = contactsGranted,
                showPermissionDeniedDialog = !micGranted,
                isPermissionPermanentlyDenied = permanentlyDenied,
                permissionDialogTitle = if (permanentlyDenied) "Permission Denied Permanently" else "Microphone Permission Required",
                permissionDialogMessage = if (permanentlyDenied) {
                    "Microphone permission is required to talk with Poxi. Please enable it in Settings."
                } else {
                    "Poxi needs microphone access to listen to your voice and talk back."
                },
                statusMessage = if (!micGranted) "Microphone permission is required" else "Microphone access ready • Tap mic to start"
            )
        }
    }

    fun openAppSettings(activity: android.app.Activity? = null) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun toggleListening() {
        toggleVoiceSession()
    }

    fun dismissPermissionDialog() {
        _uiState.update { it.copy(showPermissionDeniedDialog = false) }
    }

    /**
     * Toggles continuous voice mode.
     * Prevents race conditions or duplicate voice sessions.
     */
    fun toggleVoiceSession() {
        if (_uiState.value.isVoiceSessionActive) {
            stopVoiceSession()
        } else {
            if (!PermissionValidationLayer.hasRecordAudioPermission(context)) {
                _uiState.update {
                    it.copy(
                        hasMicrophonePermission = false,
                        showPermissionDeniedDialog = true,
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
     * Single tap starts continuous listening.
     */
    fun startVoiceSession() {
        if (!PermissionValidationLayer.hasRecordAudioPermission(context)) {
            _uiState.update {
                it.copy(
                    hasMicrophonePermission = false,
                    showPermissionDeniedDialog = true,
                    statusMessage = "Microphone permission required to start voice session"
                )
            }
            return
        }

        // Prevent duplicate simultaneous sessions
        if (_uiState.value.isVoiceSessionActive) {
            return
        }

        autoRestartJob?.cancel()
        VoiceLogger.logSessionStart()
        _uiState.update {
            it.copy(
                sessionState = VoiceSessionState.LISTENING,
                isVoiceSessionActive = true,
                isListening = true,
                isSpeaking = false,
                isProcessing = false,
                showPermissionDeniedDialog = false,
                statusMessage = "Listening... speak anytime"
            )
        }
        PoxiVoiceService.start(context)
        speechInputManager.startListening()
    }

    /**
     * Stops continuous voice session, stops speech recognizer, halts audio playback,
     * completely releases the microphone, and terminates the Foreground Service.
     */
    fun stopVoiceSession() {
        if (!_uiState.value.isVoiceSessionActive && !_uiState.value.isListening && !_uiState.value.isSpeaking) {
            return
        }

        autoRestartJob?.cancel()
        VoiceLogger.logSessionStop()
        _uiState.update {
            it.copy(
                sessionState = VoiceSessionState.STOPPING,
                isVoiceSessionActive = false,
                isListening = false,
                isSpeaking = false,
                isProcessing = false,
                audioAmplitude = 0f,
                statusMessage = "Voice session stopped • Tap mic to start"
            )
        }
        speechInputManager.stopListening()
        voiceOutputManager.stop(notifyFinished = false)
        PoxiVoiceService.stop(context)
        _uiState.update { it.copy(sessionState = VoiceSessionState.IDLE) }
    }

    /**
     * Immediately silences Poxi when user interrupts, and immediately re-arms the mic.
     */
    fun interruptSpeaking() {
        autoRestartJob?.cancel()
        VoiceLogger.logUserInterrupted()
        voiceOutputManager.stop(notifyFinished = false)
        _uiState.update {
            it.copy(
                sessionState = if (it.isVoiceSessionActive) VoiceSessionState.LISTENING else VoiceSessionState.IDLE,
                isSpeaking = false,
                isListening = it.isVoiceSessionActive,
                statusMessage = "Listening for your command..."
            )
        }
        if (_uiState.value.isVoiceSessionActive) {
            autoRestartJob = viewModelScope.launch {
                delay(100)
                if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking) {
                    _uiState.update { it.copy(sessionState = VoiceSessionState.LISTENING, isListening = true) }
                    speechInputManager.resumeListening()
                }
            }
        }
    }

    fun processUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank() || _uiState.value.isProcessing) return

        // If currently speaking, stop immediately
        autoRestartJob?.cancel()
        voiceOutputManager.stop(notifyFinished = false)
        speechInputManager.pauseListening()

        // Append user message
        val userMsg = ChatMessage(
            role = MessageRole.USER,
            text = trimmed
        )

        _uiState.update { state ->
            state.copy(
                sessionState = VoiceSessionState.PROCESSING,
                messages = state.messages + userMsg,
                isProcessing = true,
                isListening = false,
                isSpeaking = false,
                statusMessage = "Poxi is thinking..."
            )
        }

        viewModelScope.launch {
            try {
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
                                sessionState = if (state.isVoiceSessionActive) VoiceSessionState.LISTENING else VoiceSessionState.IDLE,
                                isListening = state.isVoiceSessionActive,
                                statusMessage = if (state.isVoiceSessionActive) "Listening... speak anytime" else "Error processing request"
                            )
                        }
                        if (_uiState.value.isVoiceSessionActive) {
                            autoRestartJob?.cancel()
                            autoRestartJob = viewModelScope.launch {
                                delay(150)
                                if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking && !_uiState.value.isProcessing) {
                                    speechInputManager.resumeListening()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PoxiViewModel", "Error in processUserInput: ${e.javaClass.simpleName}: ${e.message}", e)
                val errorMsg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    text = "Connection issue. Please try again."
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + errorMsg,
                        isProcessing = false,
                        sessionState = if (state.isVoiceSessionActive) VoiceSessionState.LISTENING else VoiceSessionState.IDLE,
                        isListening = state.isVoiceSessionActive,
                        statusMessage = if (state.isVoiceSessionActive) "Listening... speak anytime" else "Could not process request"
                    )
                }
                if (_uiState.value.isVoiceSessionActive) {
                    autoRestartJob?.cancel()
                    autoRestartJob = viewModelScope.launch {
                        delay(150)
                        if (_uiState.value.isVoiceSessionActive && !_uiState.value.isSpeaking && !_uiState.value.isProcessing) {
                            speechInputManager.resumeListening()
                        }
                    }
                }
            }
        }
    }

    fun selectDisambiguatedContact(contact: ContactItem) {
        _uiState.update { it.copy(pendingContactDisambiguation = null) }
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

    fun setSpeechRate(rate: Float) {
        voiceOutputManager.speechRate = rate
        _uiState.update { it.copy(speechRate = rate) }
    }

    fun setSpeechPitch(pitch: Float) {
        voiceOutputManager.speechPitch = pitch
        _uiState.update { it.copy(speechPitch = pitch) }
    }

    fun testTtsVoice(sampleText: String, languageHint: String? = null) {
        autoRestartJob?.cancel()
        voiceOutputManager.speak(text = sampleText, languageHint = languageHint, preferTts = true)
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
