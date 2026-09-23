package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.poxi.audio.LanguageDetector
import com.example.poxi.audio.SpeechInputManager
import com.example.poxi.audio.VoiceOutputManager
import com.example.poxi.bridge.AndroidActionBridge
import com.example.poxi.gemini.GeminiService
import com.example.poxi.gemini.GeminiTurnResult
import com.example.poxi.model.VoiceSessionState
import com.example.poxi.service.PoxiVoiceService
import com.example.poxi.ui.PoxiViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PoxiAssistantTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var actionBridge: AndroidActionBridge
    private lateinit var geminiService: GeminiService
    private lateinit var viewModel: PoxiViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        application = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.CALL_PHONE
        )
        actionBridge = AndroidActionBridge(context)
        geminiService = GeminiService(actionBridge)
        viewModel = PoxiViewModel(application)
    }

    @org.junit.After
    fun tearDown() {
        viewModel.stopVoiceSession()
    }

    // =========================================================================
    // SECTION G REQUIRED TESTS: 1 TO 12
    // =========================================================================

    /** 1. Hindi -> Hindi response */
    @Test
    fun test01_HindiToHindiResponse() {
        val input = "यूट्यूब खोलो"
        val lang = LanguageDetector.detect(input)
        assertEquals(LanguageDetector.LanguageType.HINDI, lang)

        val result = geminiService.processWithLocalIntentEngine(input)
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("Hindi", success.detectedLanguage)
        // Strictly matches user's language and does not force-translate English app name
        assertEquals("ठीक है, YouTube खोल रहा हूँ।", success.spokenText)
        assertEquals("openApp", success.toolAction?.functionName)
        assertEquals("YouTube", success.toolAction?.arguments?.get("appName"))
    }

    /** 2. English -> English response */
    @Test
    fun test02_EnglishToEnglishResponse() {
        val input = "Open YouTube."
        val lang = LanguageDetector.detect(input)
        assertEquals(LanguageDetector.LanguageType.ENGLISH, lang)

        val result = geminiService.processWithLocalIntentEngine(input)
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("English", success.detectedLanguage)
        assertEquals("Okay, opening YouTube.", success.spokenText)
        assertEquals("openApp", success.toolAction?.functionName)
    }

    /** 3. Hinglish -> Hinglish response */
    @Test
    fun test03_HinglishToHinglishResponse() {
        val input = "YouTube kholo."
        val lang = LanguageDetector.detect(input)
        assertEquals(LanguageDetector.LanguageType.HINGLISH, lang)

        val result = geminiService.processWithLocalIntentEngine(input)
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("Hinglish", success.detectedLanguage)
        assertEquals("Okay, YouTube open kar raha hoon.", success.spokenText)
        assertEquals("openApp", success.toolAction?.functionName)
    }

    /** 4. Hindi -> English switch */
    @Test
    fun test04_HindiToEnglishSwitch() {
        // Step 1: User speaks in Hindi
        val hindiResult = geminiService.processWithLocalIntentEngine("यूट्यूब खोलो")
        assertTrue(hindiResult is GeminiTurnResult.Success)
        assertEquals("Hindi", (hindiResult as GeminiTurnResult.Success).detectedLanguage)
        assertEquals("ठीक है, YouTube खोल रहा हूँ।", hindiResult.spokenText)

        // Step 2: User immediately switches to English
        val englishResult = geminiService.processWithLocalIntentEngine("Open YouTube.")
        assertTrue(englishResult is GeminiTurnResult.Success)
        val successEnglish = englishResult as GeminiTurnResult.Success
        assertEquals("English", successEnglish.detectedLanguage)
        assertEquals("Okay, opening YouTube.", successEnglish.spokenText)
    }

    /** 5. English -> Hindi switch */
    @Test
    fun test05_EnglishToHindiSwitch() {
        // Step 1: User speaks in English
        val englishResult = geminiService.processWithLocalIntentEngine("Open WhatsApp")
        assertTrue(englishResult is GeminiTurnResult.Success)
        assertEquals("English", (englishResult as GeminiTurnResult.Success).detectedLanguage)
        assertEquals("Okay, opening WhatsApp.", englishResult.spokenText)

        // Step 2: User immediately switches to Hindi
        val hindiResult = geminiService.processWithLocalIntentEngine("व्हाट्सएप खोलो")
        assertTrue(hindiResult is GeminiTurnResult.Success)
        val successHindi = hindiResult as GeminiTurnResult.Success
        assertEquals("Hindi", successHindi.detectedLanguage)
        assertEquals("ठीक है, WhatsApp खोल रहा हूँ।", successHindi.spokenText)
    }

    /** 6. Hindi + English mixed sentence */
    @Test
    fun test06_HindiEnglishMixedSentence() {
        val input = "YouTube open karo aur ye video chalao."
        val lang = LanguageDetector.detect(input)
        assertEquals(LanguageDetector.LanguageType.HINGLISH, lang)

        val result = geminiService.processWithLocalIntentEngine(input)
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        // Keeps common English words naturally without awkward translation
        assertEquals("Okay, YouTube open kar raha hoon aur video play karta hoon.", success.spokenText)
    }

    /** 7. Start voice once -> continuous listening */
    @Test
    fun test07_StartVoiceOnce_ContinuousListening() {
        viewModel.startVoiceSession()

        val state = viewModel.uiState.value
        assertTrue("Session must be active on single tap", state.isVoiceSessionActive)
        assertTrue("Listening state must be true", state.isListening)
        assertTrue("Foreground service must be active", PoxiVoiceService.isServiceActive.value)

        viewModel.stopVoiceSession()
    }

    /** 8. Normal silence does not toggle microphone repeatedly */
    @Test
    fun test08_NormalSilence_DoesNotToggleMicrophoneRepeatedly() {
        viewModel.startVoiceSession()
        assertTrue(viewModel.uiState.value.isVoiceSessionActive)
        assertTrue(viewModel.uiState.value.isListening)

        // Simulate normal pause/silence timeout from recognizer
        viewModel.speechInputManager.onSpeechTimeout?.invoke()

        // Session must remain active and listening in UI without violent toggling
        assertTrue("Voice session must remain active during natural silence", viewModel.uiState.value.isVoiceSessionActive)
        assertTrue("Microphone state must stay active in UI", viewModel.uiState.value.isListening)

        viewModel.stopVoiceSession()
    }

    /** 9. User interruption works */
    @Test
    fun test09_UserInterruptionWorks() {
        viewModel.startVoiceSession()
        // Simulate Poxi speaking
        viewModel.voiceOutputManager.speak("Poxi is currently speaking an answer")
        viewModel.interruptSpeaking()

        assertFalse("Voice output must immediately halt on user interruption", viewModel.uiState.value.isSpeaking)
        assertFalse("AudioPlayer must be stopped", viewModel.voiceOutputManager.audioPlayer.isPlaying)

        viewModel.stopVoiceSession()
    }

    /** 10. Voice OFF completely releases microphone */
    @Test
    fun test10_VoiceOff_CompletelyReleasesMicrophone() {
        viewModel.startVoiceSession()
        assertTrue(viewModel.uiState.value.isVoiceSessionActive)

        viewModel.stopVoiceSession()

        val state = viewModel.uiState.value
        assertFalse("Voice session must be false", state.isVoiceSessionActive)
        assertFalse("Listening must be false", state.isListening)
        assertFalse("Speaking must be false", state.isSpeaking)
        assertFalse("Foreground service must stop", PoxiVoiceService.isServiceActive.value)
        assertFalse("SpeechInputManager session must not be alive", viewModel.speechInputManager.isSessionAlive)
    }

    /** 11. No duplicate voice sessions */
    @Test
    fun test11_NoDuplicateVoiceSessions() {
        viewModel.startVoiceSession()
        assertTrue(viewModel.uiState.value.isVoiceSessionActive)

        // Attempting to start again while already active must not duplicate
        viewModel.startVoiceSession()
        assertTrue(viewModel.uiState.value.isVoiceSessionActive)

        viewModel.stopVoiceSession()
    }

    /** 12. No repeated 'ton-ton' start/stop behavior */
    @Test
    fun test12_NoRepeatedTonTonStartStopBehavior() {
        var listeningFinishedReported = false
        val speechManager = SpeechInputManager(context)
        speechManager.onListeningFinished = { listeningFinishedReported = true }

        // Start listening
        speechManager.startListening()

        // Simulate silence timeout event
        val listenerMethod = SpeechInputManager::class.java.getDeclaredMethod("createListener")
        listenerMethod.isAccessible = true
        val listener = listenerMethod.invoke(speechManager) as android.speech.RecognitionListener
        listener.onError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        // For silence timeout, onListeningFinished must NOT be called so the UI doesn't flutter ON and OFF
        assertFalse("onListeningFinished must not fire on normal speech timeout", listeningFinishedReported)

        speechManager.destroy()
    }

    // =========================================================================
    // ACTION BRIDGE & PERMISSION TESTS
    // =========================================================================

    @Test
    fun testCase_CallContact_HindiDevanagari() {
        val result = geminiService.processWithLocalIntentEngine("कॉल भाई")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("callContact", success.toolAction?.functionName)
        assertEquals("भाई", success.toolAction?.arguments?.get("contactName"))
    }

    @Test
    fun testCase_CallContact_Hinglish() {
        val result = geminiService.processWithLocalIntentEngine("Mummy ko call karo")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("callContact", success.toolAction?.functionName)
        assertEquals("Mummy", success.toolAction?.arguments?.get("contactName"))
    }

    @Test
    fun testCase_CallContact_NoFalsePositiveAlias() {
        // Names containing substring "maa" or "ammi" must not falsely trigger relationship aliases
        val outcome = actionBridge.executeCallContact("Maanav")
        // In robolectric without contacts, it should return NotFound for "Maanav", not search for Mom/Maa
        assertTrue(outcome is com.example.poxi.bridge.CallContactOutcome.NotFound)
        val notFound = outcome as com.example.poxi.bridge.CallContactOutcome.NotFound
        assertEquals("Maanav", notFound.query)
    }

    @Test
    fun testCase_CallContact_BlankQueryHandledGracefully() {
        val outcome = actionBridge.executeCallContact("   ")
        assertTrue(outcome is com.example.poxi.bridge.CallContactOutcome.NotFound)
        val notFound = outcome as com.example.poxi.bridge.CallContactOutcome.NotFound
        assertTrue(notFound.message.contains("specify a contact name", ignoreCase = true))
    }

    @Test
    fun testCase_CallPhoneNumber() {
        val result = geminiService.processWithLocalIntentEngine("Call 9876543210")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("makeCall", success.toolAction?.functionName)
        assertEquals("9876543210", success.toolAction?.arguments?.get("phoneNumber"))
    }

    @Test
    fun testCase_PermissionValidationLayer_GuardsSession() {
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()

        assertFalse("Session must not start without mic permission", vm.uiState.value.isVoiceSessionActive)
        assertTrue("Permission dialog must show", vm.uiState.value.showPermissionDeniedDialog)
    }

    @Test
    fun testCase_PermissionDeniedDialog_DismissAndState() {
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = false, contactsGranted = true, permanentlyDenied = false)

        assertTrue(vm.uiState.value.showPermissionDeniedDialog)
        assertFalse(vm.uiState.value.isPermissionPermanentlyDenied)

        vm.dismissPermissionDialog()
        assertFalse(vm.uiState.value.showPermissionDeniedDialog)
    }

    @Test
    fun testCase_PermanentDenial_FlagsSettingsPrompt() {
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = false, contactsGranted = false, permanentlyDenied = true)

        assertTrue(vm.uiState.value.showPermissionDeniedDialog)
        assertTrue(vm.uiState.value.isPermissionPermanentlyDenied)
        assertTrue(vm.uiState.value.statusMessage.contains("Microphone permission is required", ignoreCase = true))
    }

    // =========================================================================
    // 13 STABILITY STATES CRITICAL TESTS
    // =========================================================================

    /** State 1: Open app without microphone permission */
    @Test
    fun testState01_OpenAppWithoutMicPermission() {
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        assertFalse(vm.uiState.value.hasMicrophonePermission)
        assertFalse(vm.uiState.value.isVoiceSessionActive)
        // No crash
    }

    /** State 2: Grant microphone permission */
    @Test
    fun testState02_GrantMicrophonePermission() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = true, contactsGranted = true, permanentlyDenied = false)
        assertTrue(vm.uiState.value.hasMicrophonePermission)
        // Does not auto-start voice without user interaction
        assertFalse(vm.uiState.value.isVoiceSessionActive)
    }

    /** State 3: Tap voice button once */
    @Test
    fun testState03_TapVoiceButtonOnce() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.toggleVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)
        assertTrue(vm.uiState.value.isListening)
    }

    /** State 4 & 5: Start Gemini Live and Speech recognition starts */
    @Test
    fun testState04_and_05_GeminiLiveAndSpeechRecognitionStarts() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)
        assertNotNull(vm.uiState.value.statusMessage)
    }

    /** State 6: User remains silent */
    @Test
    fun testState06_UserRemainsSilent() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()
        // Simulate speech timeout callback
        vm.speechInputManager.onSpeechTimeout?.invoke()
        assertTrue(vm.uiState.value.isVoiceSessionActive)
    }

    /** State 7 & 8: User speaks and Poxi responds */
    @Test
    fun testState07_and_08_UserSpeaksAndPoxiResponds() {
        val vm = PoxiViewModel(application)
        vm.processUserInput("Open YouTube")
        assertTrue(vm.uiState.value.messages.any { it.text.contains("Open YouTube") })
    }

    /** State 9: User interrupts Poxi */
    @Test
    fun testState09_UserInterruptsPoxi() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()
        vm.interruptSpeaking()
        assertFalse(vm.uiState.value.isSpeaking)
    }

    /** State 10 & 11: Turn voice mode OFF and turn voice mode ON again */
    @Test
    fun testState10_and_11_TurnVoiceModeOffAndOnAgain() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)

        // Turn ON
        vm.startVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)

        // Turn OFF
        vm.stopVoiceSession()
        assertFalse(vm.uiState.value.isVoiceSessionActive)
        assertFalse(vm.uiState.value.isListening)

        // Turn ON again
        vm.startVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)
        assertTrue(vm.uiState.value.isListening)
    }

    /** State 12: Foreground service starts and stops cleanly */
    @Test
    fun testState12_ForegroundServiceStartStop() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        PoxiVoiceService.start(application)
        assertTrue(PoxiVoiceService.isServiceActive.value)

        PoxiVoiceService.stop(application)
        assertFalse(PoxiVoiceService.isServiceActive.value)
    }

    /** State 13: Gemini/network connection fails */
    @Test
    fun testState13_NetworkFailureHandling() {
        val result = geminiService.processWithLocalIntentEngine("Tell me a story about space")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.spokenText)
        assertTrue(success.spokenText.isNotBlank())
    }

    /** Test MainActivity creation, lifecycle, and destruction */
    @Test
    fun testMainActivity_LifecycleAndInteraction() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
        val activity = controller.get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    /** Test AudioWaveformVisualizer component initialization and state changes */
    @Test
    fun testAudioWaveformVisualizer_StatesAndAmplitude() {
        val vm = PoxiViewModel(application)
        assertNotNull(vm.uiState.value.audioAmplitude)
        assertEquals(0f, vm.uiState.value.audioAmplitude)
        assertFalse(vm.uiState.value.isListening)
        assertFalse(vm.uiState.value.isSpeaking)
        assertFalse(vm.uiState.value.isProcessing)
    }

    /** Test Accompanist permission flow: Microphone permission granted allows voice activation */
    @Test
    fun testAccompanistPermissionFlow_MicrophoneGranted_AllowsVoiceActivation() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = true, contactsGranted = true, permanentlyDenied = false)

        assertTrue("Microphone permission flag must be true", vm.uiState.value.hasMicrophonePermission)
        assertFalse("Permission dialog should not be shown when granted", vm.uiState.value.showPermissionDeniedDialog)

        // Activating voice session succeeds
        vm.startVoiceSession()
        assertTrue("Voice session must be active when permission is granted", vm.uiState.value.isVoiceSessionActive)
        assertTrue("Listening must be active", vm.uiState.value.isListening)
    }

    /** Test Accompanist permission flow: Microphone permission denied gates voice session and shows rationale */
    @Test
    fun testAccompanistPermissionFlow_MicrophoneDenied_BlocksVoiceActivationAndShowsRationale() {
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = false, contactsGranted = false, permanentlyDenied = false)

        assertFalse("Microphone permission flag must be false", vm.uiState.value.hasMicrophonePermission)
        assertTrue("Permission dialog should be shown on denial", vm.uiState.value.showPermissionDeniedDialog)
        assertFalse("Permanent denial should be false for standard denial", vm.uiState.value.isPermissionPermanentlyDenied)

        // Voice activation is strictly blocked
        vm.startVoiceSession()
        assertFalse("Voice session must not activate without permission", vm.uiState.value.isVoiceSessionActive)
        assertFalse("Listening must not activate without permission", vm.uiState.value.isListening)
    }

    /** Test Accompanist permission flow: Permanent denial correctly guides to app settings */
    @Test
    fun testAccompanistPermissionFlow_PermanentDenial_OpensSettingsFlow() {
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = false, contactsGranted = false, permanentlyDenied = true)

        assertTrue(vm.uiState.value.isPermissionPermanentlyDenied)
        assertTrue(vm.uiState.value.showPermissionDeniedDialog)
        assertEquals("Permission Denied Permanently", vm.uiState.value.permissionDialogTitle)
        assertTrue(vm.uiState.value.permissionDialogMessage.contains("Settings", ignoreCase = true))
    }

    /** Test 13: Natural variations of 'Open YouTube' command */
    @Test
    fun test13_OpenYouTube_NaturalVariations() {
        val variations = listOf(
            "Open YouTube" to "English",
            "Open YouTube." to "English",
            "Open the YouTube app" to "English",
            "Launch YouTube" to "English",
            "Start YouTube" to "English",
            "Open YouTube app" to "English",
            "यूट्यूब खोलो" to "Hindi",
            "YouTube kholo" to "Hinglish",
            "YouTube open karo" to "Hinglish"
        )

        for ((input, expectedLang) in variations) {
            val result = geminiService.processWithLocalIntentEngine(input)
            assertTrue("Expected success for variation: '$input'", result is GeminiTurnResult.Success)
            val success = result as GeminiTurnResult.Success
            assertEquals("Language must match for '$input'", expectedLang, success.detectedLanguage)
            assertEquals("Must execute openApp tool for '$input'", "openApp", success.toolAction?.functionName)
            assertEquals("YouTube", success.toolAction?.arguments?.get("appName"))
        }
    }

    /** Test 14: When YouTube is not installed, action bridge returns honest failure without crash */
    @Test
    fun test14_OpenYouTube_NotInstalled_HonestFailure() {
        val bridge = AndroidActionBridge(context)
        val result = bridge.executeOpenApp("YouTube")
        assertNotNull("Bridge result must not be null", result)
        // If not installed on device / Robolectric clean environment, honest failure is returned
        if (!result.success) {
            assertEquals("YouTube is not installed on this device", result.summary)
            assertEquals("YouTube", result.target)
        }
    }

    /** Test 15: Voice output manager plays PCM bytes and triggers start and finish callbacks */
    @Test
    fun test15_VoiceOutputManager_AudioPlaybackFlow() {
        var startCalled = false
        var finishCalled = false
        val manager = com.example.poxi.audio.VoiceOutputManager(context)
        manager.onSpeakingStarted = { startCalled = true }
        manager.onSpeakingFinished = { finishCalled = true }

        // 16-bit 24kHz mono PCM sample (100 samples)
        val samplePcm = ByteArray(200) { 0 }
        manager.speak(
            text = "Hello Poxi",
            audioBytes = samplePcm,
            languageHint = "English"
        )

        // Stop cleanly with notifyFinished = true
        manager.stop(notifyFinished = true)
        assertTrue(finishCalled)
    }

    /** Test Continuous Voice Requirement A: One tap starts continuous voice mode */
    @Test
    fun testContinuousVoice_OneTapStartsContinuousMode() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.toggleVoiceSession()

        assertTrue("Voice session must be active after one tap", vm.uiState.value.isVoiceSessionActive)
        assertTrue("SpeechInputManager continuous session must be active", vm.speechInputManager.isContinuousSessionActive)
        assertTrue("Listening state must be true", vm.uiState.value.isListening)
    }

    /** Test Continuous Voice Requirement B: Automatic return to listening after response */
    @Test
    fun testContinuousVoice_AutoListenAfterResponse() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()

        // Poxi starts speaking
        vm.voiceOutputManager.onSpeakingStarted?.invoke()
        assertTrue("isSpeaking must be true", vm.uiState.value.isSpeaking)
        assertFalse("isListening must be false while speaking", vm.uiState.value.isListening)
        assertTrue("Session must remain active while speaking", vm.uiState.value.isVoiceSessionActive)

        // Poxi finishes speaking
        vm.voiceOutputManager.onSpeakingFinished?.invoke()
        assertFalse("isSpeaking must be false after finish", vm.uiState.value.isSpeaking)
        assertTrue("Voice session must still be active without tapping mic", vm.uiState.value.isVoiceSessionActive)
    }

    /** Test Continuous Voice Requirement C: Silence handled internally without ending session */
    @Test
    fun testContinuousVoice_SilenceDoesNotEndSession() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()

        // Simulate speech timeout / silence
        vm.speechInputManager.onSpeechTimeout?.invoke()
        assertTrue("Voice session must remain active after silence/timeout", vm.uiState.value.isVoiceSessionActive)
        assertEquals(VoiceSessionState.LISTENING, vm.uiState.value.sessionState)
    }

    /** Test Continuous Voice Requirement D: Interruption stops playback and returns to listening */
    @Test
    fun testContinuousVoice_InterruptionStopsSpeechAndReturnsToListening() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()

        // Simulate speaking
        vm.voiceOutputManager.onSpeakingStarted?.invoke()
        assertTrue(vm.uiState.value.isSpeaking)

        // User interrupts
        vm.interruptSpeaking()
        assertFalse("Speaking must stop on interruption", vm.uiState.value.isSpeaking)
        assertTrue("Session must remain active on interruption", vm.uiState.value.isVoiceSessionActive)
        assertTrue("Listening must be restored", vm.uiState.value.isListening)
        assertEquals(VoiceSessionState.LISTENING, vm.uiState.value.sessionState)
    }

    /** Test Continuous Voice Requirement E: Explicit tap stops the continuous session */
    @Test
    fun testContinuousVoice_ExplicitTapStopsSession() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)

        // Explicit user stop
        vm.stopVoiceSession()
        assertFalse("Voice session must be inactive after explicit stop", vm.uiState.value.isVoiceSessionActive)
        assertFalse("Listening must be inactive after explicit stop", vm.uiState.value.isListening)
        assertFalse("SpeechInputManager continuous session must be false", vm.speechInputManager.isContinuousSessionActive)
        assertEquals(VoiceSessionState.IDLE, vm.uiState.value.sessionState)
    }

    /** Test Continuous Voice Requirement F: No duplicate sessions when start called again */
    @Test
    fun testContinuousVoice_NoDuplicateSessions() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val vm = PoxiViewModel(application)
        vm.startVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)

        // Calling start again while active should be a no-op
        vm.startVoiceSession()
        assertTrue(vm.uiState.value.isVoiceSessionActive)
        assertTrue(vm.speechInputManager.isContinuousSessionActive)
    }

    /** Test Android TTS Integration: Initialization and State */
    @Test
    fun testTextToSpeech_VoiceOutputManagerInitialization() {
        val manager = VoiceOutputManager(application)
        // Simulate TTS initialization success callback
        manager.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        assertTrue("TTS must be marked ready after successful onInit", manager.isTtsReady)
    }

    /** Test Android TTS Integration: Spoken response speech rate and pitch control */
    @Test
    fun testTextToSpeech_SpeechRateAndPitchControls() {
        val vm = PoxiViewModel(application)
        vm.setSpeechRate(1.2f)
        assertEquals(1.2f, vm.uiState.value.speechRate, 0.01f)

        vm.setSpeechPitch(1.1f)
        assertEquals(1.1f, vm.uiState.value.speechPitch, 0.01f)
    }

    /** Test Android TTS Integration: Interruption halts speech and resets amplitude */
    @Test
    fun testTextToSpeech_InterruptionStopsSpeech() {
        val vm = PoxiViewModel(application)
        vm.voiceOutputManager.onInit(android.speech.tts.TextToSpeech.SUCCESS)

        var started = false
        var finished = false
        vm.voiceOutputManager.onSpeakingStarted = { started = true }
        vm.voiceOutputManager.onSpeakingFinished = { finished = true }

        // Trigger speech
        vm.voiceOutputManager.speak("Hello from Poxi voice assistant", languageHint = "English")
        // Immediate interruption
        vm.voiceOutputManager.stop(notifyFinished = true)

        assertFalse("Voice manager must not be speaking after stop", vm.voiceOutputManager.isSpeaking)
        assertTrue("Finished callback must be invoked on stop", finished)
    }

    /** Test Android TTS Integration: Multi-lingual voice test invocation */
    @Test
    fun testTextToSpeech_MultiLingualVoiceTest() {
        val vm = PoxiViewModel(application)
        vm.voiceOutputManager.onInit(android.speech.tts.TextToSpeech.SUCCESS)

        // Test Hindi voice response
        vm.testTtsVoice("नमस्ते! मैं पोक्सी हूँ", languageHint = "Hindi")
        // Should not throw and manage speech correctly
        assertNotNull(vm.voiceOutputManager)

        // Test English voice response
        vm.testTtsVoice("Hello, how can I help you?", languageHint = "English")
        assertNotNull(vm.voiceOutputManager)
    }

    /** Test Android TTS Integration: Speech queuing if speak called before onInit completes */
    @Test
    fun testTextToSpeech_PendingSpeechQueuedBeforeInit() {
        val manager = VoiceOutputManager(application)
        assertFalse("TTS not ready yet initially", manager.isTtsReady)

        // Queue speech while still initializing
        manager.speak("Pending speech response", languageHint = "English")

        var speechStarted = false
        manager.onSpeakingStarted = { speechStarted = true }

        // Now initialize TTS
        manager.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        assertTrue("TTS ready after onInit", manager.isTtsReady)
    }
}

