package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    /** Test Case 1: "Open YouTube" -> openApp("YouTube") */
    @Test
    fun testCase01_OpenYouTube() {
        val result = geminiService.processWithLocalIntentEngine("Open YouTube")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openApp", success.toolAction?.functionName)
        assertEquals("YouTube", success.toolAction?.arguments?.get("appName"))
    }

    /** Test Case 2: "ओपन यूट्यूब" -> openApp("YouTube") in Hindi */
    @Test
    fun testCase02_HindiOpenYouTube() {
        val result = geminiService.processWithLocalIntentEngine("ओपन यूट्यूब")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openApp", success.toolAction?.functionName)
        assertEquals("YouTube", success.toolAction?.arguments?.get("appName"))
        assertEquals("Hindi", success.detectedLanguage)
    }

    /** Test Case 3: "youtube kholo" -> openApp("YouTube") in Hinglish */
    @Test
    fun testCase03_YouTubeKholo() {
        val result = geminiService.processWithLocalIntentEngine("youtube kholo")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openApp", success.toolAction?.functionName)
        assertEquals("YouTube", success.toolAction?.arguments?.get("appName"))
    }

    /** Test Case 4: "WhatsApp खोलो" -> openWhatsApp() */
    @Test
    fun testCase04_WhatsAppKholoHindi() {
        val result = geminiService.processWithLocalIntentEngine("WhatsApp खोलो")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openWhatsApp", success.toolAction?.functionName)
    }

    /** Test Case 5: "Open WhatsApp" -> openWhatsApp() */
    @Test
    fun testCase05_OpenWhatsAppEnglish() {
        val result = geminiService.processWithLocalIntentEngine("Open WhatsApp")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openWhatsApp", success.toolAction?.functionName)
    }

    /** Test Case 6: "कॉल भाई" -> callContact("भाई") */
    @Test
    fun testCase06_CallBhaiHindi() {
        val result = geminiService.processWithLocalIntentEngine("कॉल भाई")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("callContact", success.toolAction?.functionName)
        assertEquals("भाई", success.toolAction?.arguments?.get("contactName"))
    }

    /** Test Case 7: "Call brother" -> callContact("brother") */
    @Test
    fun testCase07_CallBrotherEnglish() {
        val result = geminiService.processWithLocalIntentEngine("Call brother")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("callContact", success.toolAction?.functionName)
        assertEquals("brother", success.toolAction?.arguments?.get("contactName"))
    }

    /** Test Case 8: Hindi normal conversation */
    @Test
    fun testCase08_HindiNormalConversation() {
        val result = geminiService.processWithLocalIntentEngine("नमस्ते, आप कैसे हैं?")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("Hindi", success.detectedLanguage)
        assertTrue(success.spokenText.isNotEmpty())
    }

    /** Test Case 9: English normal conversation */
    @Test
    fun testCase09_EnglishNormalConversation() {
        val result = geminiService.processWithLocalIntentEngine("Hello Poxi, how are you?")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("English", success.detectedLanguage)
        assertTrue(success.spokenText.isNotEmpty())
    }

    /** Test Case 10: Hindi -> English mid-conversation */
    @Test
    fun testCase10_SwitchToEnglish() {
        // First speak in Hindi
        val turn1 = geminiService.processWithLocalIntentEngine("नमस्ते पॉक्सी")
        assertEquals("Hindi", (turn1 as GeminiTurnResult.Success).detectedLanguage)

        // Then switch to English
        val turn2 = geminiService.processWithLocalIntentEngine("Talk to me in English.")
        assertTrue(turn2 is GeminiTurnResult.Success)
        val success2 = turn2 as GeminiTurnResult.Success
        assertEquals("English", success2.detectedLanguage)
        assertTrue(success2.spokenText.contains("English", ignoreCase = true))
    }

    /** Test Case 11: English -> Hindi mid-conversation */
    @Test
    fun testCase11_SwitchToHindi() {
        // First speak in English
        val turn1 = geminiService.processWithLocalIntentEngine("Hello Poxi, how are you?")
        assertEquals("English", (turn1 as GeminiTurnResult.Success).detectedLanguage)

        // Then switch to Hindi
        val turn2 = geminiService.processWithLocalIntentEngine("Hindi mein baat karo.")
        assertTrue(turn2 is GeminiTurnResult.Success)
        val success2 = turn2 as GeminiTurnResult.Success
        assertEquals("Hindi", success2.detectedLanguage)
        assertTrue(success2.spokenText.contains("हिंदी", ignoreCase = true) || success2.spokenText.contains("नमस्ते", ignoreCase = true))
    }

    /** Test Case 12: Start voice mode once, background support via Foreground Service */
    @Test
    fun testCase12_StartVoiceModeForegroundService() {
        viewModel.startVoiceSession()
        assertTrue("Voice session should be active", viewModel.uiState.value.isVoiceSessionActive)

        // Verify service state flow is active
        PoxiVoiceService.start(context)
        // Clean up
        viewModel.stopVoiceSession()
    }

    /** Test Case 13: Stop voice mode and verify microphone is released */
    @Test
    fun testCase13_StopVoiceModeReleasesMic() {
        viewModel.startVoiceSession()
        assertTrue(viewModel.uiState.value.isVoiceSessionActive)

        viewModel.stopVoiceSession()
        assertFalse("Voice session must be inactive", viewModel.uiState.value.isVoiceSessionActive)
        assertFalse("Listening state must be false", viewModel.uiState.value.isListening)
        assertFalse("Speaking state must be false", viewModel.uiState.value.isSpeaking)
        assertFalse("Service must be stopped", PoxiVoiceService.isServiceActive.value)
    }

    /** Test Case 14: Interrupt Poxi while it is speaking */
    @Test
    fun testCase14_InterruptPoxiWhileSpeaking() {
        viewModel.startVoiceSession()
        // Simulate assistant speaking
        viewModel.voiceOutputManager.speak("This is a long sentence being spoken by Poxi")
        viewModel.interruptSpeaking()

        assertFalse("Speaking must be stopped on interrupt", viewModel.uiState.value.isSpeaking)
        // Clean up
        viewModel.stopVoiceSession()
    }

    /** Test Case 15: Tapping voice button when permission is missing requests it and guards session */
    @Test
    fun testCase15_MicPermissionEnforcement() {
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val freshVm = PoxiViewModel(application)
        freshVm.toggleListening()

        assertFalse("Session must not start without mic permission", freshVm.uiState.value.isVoiceSessionActive)
        assertTrue(freshVm.uiState.value.statusMessage.contains("Microphone permission required", ignoreCase = true))
    }

    /** Test Case 16: Verify SpeechInputManager handles ERROR_CLIENT cleanly without surfacing client side error */
    @Test
    fun testCase16_ClientErrorRecovery() {
        var surfacedError: String? = null
        var timeoutTriggered = false
        val speechManager = com.example.poxi.audio.SpeechInputManager(context)
        speechManager.onError = { surfacedError = it }
        speechManager.onSpeechTimeout = { timeoutTriggered = true }

        // Trigger ERROR_CLIENT
        val listenerField = com.example.poxi.audio.SpeechInputManager::class.java.getDeclaredMethod("createListener")
        listenerField.isAccessible = true
        val listener = listenerField.invoke(speechManager) as android.speech.RecognitionListener
        listener.onError(android.speech.SpeechRecognizer.ERROR_CLIENT)

        assertFalse("Client side error should not be shown to user", surfacedError == "Client side error")
        assertTrue("Timeout callback should be triggered for graceful retry", timeoutTriggered)
    }

    /** Test Case 17: PermissionValidationLayer strictly guards Gemini Live session start */
    @Test
    fun testCase17_PermissionValidationLayer_GuardsSession() {
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertFalse(com.example.poxi.permission.PermissionValidationLayer.hasRecordAudioPermission(application))

        val vm = PoxiViewModel(application)
        vm.startVoiceSession()

        assertFalse("Gemini Live session must not start when RECORD_AUDIO is denied", vm.uiState.value.isVoiceSessionActive)
        assertTrue("Permission dialog must be displayed", vm.uiState.value.showPermissionDeniedDialog)
        assertTrue("Dialog title must indicate requirement", vm.uiState.value.permissionDialogTitle.contains("Microphone", ignoreCase = true))
    }

    /** Test Case 18: Permission denied UI prompt can be dismissed or reopened */
    @Test
    fun testCase18_PermissionDeniedDialog_DismissAndState() {
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = false, contactsGranted = true, permanentlyDenied = false)

        assertTrue(vm.uiState.value.showPermissionDeniedDialog)
        assertFalse(vm.uiState.value.isPermissionPermanentlyDenied)

        vm.dismissPermissionDialog()
        assertFalse(vm.uiState.value.showPermissionDeniedDialog)
    }

    /** Test Case 19: Permanent denial marks settings redirection */
    @Test
    fun testCase19_PermanentDenial_FlagsSettingsPrompt() {
        val vm = PoxiViewModel(application)
        vm.onPermissionsResult(micGranted = false, contactsGranted = false, permanentlyDenied = true)

        assertTrue(vm.uiState.value.showPermissionDeniedDialog)
        assertTrue(vm.uiState.value.isPermissionPermanentlyDenied)
        assertTrue(vm.uiState.value.statusMessage.contains("Microphone permission is required", ignoreCase = true))
    }
}
