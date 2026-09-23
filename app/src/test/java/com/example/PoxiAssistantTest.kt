package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.poxi.bridge.AndroidActionBridge
import com.example.poxi.gemini.GeminiService
import com.example.poxi.gemini.GeminiTurnResult
import org.junit.Assert.assertEquals
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
    private lateinit var actionBridge: AndroidActionBridge
    private lateinit var geminiService: GeminiService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        actionBridge = AndroidActionBridge(context)
        geminiService = GeminiService(actionBridge)
    }

    @Test
    fun testCase1_HelloPoxi() {
        val result = geminiService.processWithLocalIntentEngine("Hello Poxi.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertTrue(success.spokenText.contains("Poxi", ignoreCase = true))
    }

    @Test
    fun testCase2_HindiMeinBaatKaro() {
        val result = geminiService.processWithLocalIntentEngine("Hindi mein baat karo.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("Hindi", success.detectedLanguage)
        assertTrue(success.spokenText.isNotEmpty())
    }

    @Test
    fun testCase3_TalkToMeInEnglish() {
        val result = geminiService.processWithLocalIntentEngine("Talk to me in English.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("English", success.detectedLanguage)
        assertTrue(success.spokenText.contains("English", ignoreCase = true))
    }

    @Test
    fun testCase4_HinglishMeinBaatKaro() {
        val result = geminiService.processWithLocalIntentEngine("Hinglish mein baat karo.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertEquals("Hinglish", success.detectedLanguage)
        assertTrue(success.spokenText.contains("Hinglish", ignoreCase = true))
    }

    @Test
    fun testCase5_WhatsAppKholo() {
        val result = geminiService.processWithLocalIntentEngine("WhatsApp kholo.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openWhatsApp", success.toolAction?.functionName)
    }

    @Test
    fun testCase6_OpenWhatsApp() {
        val result = geminiService.processWithLocalIntentEngine("Open WhatsApp.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("openWhatsApp", success.toolAction?.functionName)
    }

    @Test
    fun testCase7_MummyKoCallKaro() {
        val result = geminiService.processWithLocalIntentEngine("Mummy ko call karo.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("callContact", success.toolAction?.functionName)
        assertEquals("Mummy", success.toolAction?.arguments?.get("contactName"))
    }

    @Test
    fun testCase8_CallRahul() {
        val result = geminiService.processWithLocalIntentEngine("Call Rahul.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("callContact", success.toolAction?.functionName)
        assertEquals("Rahul", success.toolAction?.arguments?.get("contactName"))
    }

    @Test
    fun testCase9_CallNumber() {
        val result = geminiService.processWithLocalIntentEngine("Call 9876543210.")
        assertTrue(result is GeminiTurnResult.Success)
        val success = result as GeminiTurnResult.Success
        assertNotNull(success.toolAction)
        assertEquals("makeCall", success.toolAction?.functionName)
        assertEquals("9876543210", success.toolAction?.arguments?.get("phoneNumber"))
    }
}
