package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.poxi.audio.LanguageDetector
import com.example.poxi.audio.SpeechInputManager
import com.example.poxi.bridge.AndroidActionBridge
import com.example.poxi.gemini.GeminiService
import com.example.poxi.gemini.GeminiTurnResult
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
}
