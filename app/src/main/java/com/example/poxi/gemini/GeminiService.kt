package com.example.poxi.gemini

import android.util.Base64
import android.util.Log
import com.example.poxi.audio.LanguageDetector
import com.example.poxi.audio.VoiceLogger
import com.example.poxi.bridge.AndroidActionBridge
import com.example.poxi.bridge.CallContactOutcome
import com.example.poxi.model.ContactItem
import com.example.poxi.model.ToolActionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class GeminiTurnResult {
    data class Success(
        val spokenText: String,
        val audioBytes: ByteArray? = null,
        val detectedLanguage: String? = null,
        val toolAction: ToolActionInfo? = null,
        val pendingContacts: List<ContactItem>? = null
    ) : GeminiTurnResult()

    data class Error(val message: String) : GeminiTurnResult()
}

class GeminiService(
    private val actionBridge: AndroidActionBridge
) {

    companion object {
        private const val TAG = "GeminiService"
        private const val PRIMARY_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val FALLBACK_MODEL = "gemini-2.5-flash"
        private const val UNIFIED_VOICE_NAME = "Aoede" // Warm, expressive multilingual voice
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val conversationHistory = mutableListOf<JSONObject>()

    init {
        resetConversation()
    }

    fun resetConversation() {
        conversationHistory.clear()
        VoiceLogger.logGeminiSessionState("SESSION_RESET")
    }

    private fun getSystemInstruction(): JSONObject {
        return JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", """
                        You are Poxi, an intelligent, cheerful, and fast voice AI assistant for Android.
                        
                        CRITICAL LANGUAGE-AWARE RESPONSE RULES:
                        1. Hindi input → Hindi response.
                           Example: User: "यूट्यूब खोलो" → Poxi: "ठीक है, YouTube खोल रहा हूँ।"
                        2. English input → English response.
                           Example: User: "Open YouTube." → Poxi: "Okay, opening YouTube."
                        3. Hinglish input → natural Hinglish response.
                           Example: User: "YouTube kholo." → Poxi: "Okay, YouTube open kar raha hoon."
                        4. Mixed Hindi + English input:
                           Do NOT force-translate English product/app/action words into awkward Hindi. Keep common English words (YouTube, WhatsApp, video, open, play, call) naturally.
                           Example: User: "YouTube open karo aur ye video chalao." → Poxi: "Okay, YouTube open kar raha hoon aur video play karta hoon."
                        5. If user switches from Hindi to English, immediately respond in English.
                        6. If user switches from English to Hindi, immediately respond in Hindi.
                        7. If user speaks Hinglish, preserve the natural Hinglish style.
                        8. NEVER announce language detection (DO NOT say "I detected Hindi", "Switching to English", etc.).
                        9. DO NOT randomly switch languages.
                        10. DO NOT translate the user's sentence unless requested.
                        11. Keep spoken voice responses concise, warm, natural, and friendly (1 short sentence) suitable for instant voice playback.
                        12. Use the same consistent voice for all responses.
                        
                        DEVICE ACTION & TOOL CALLING RULES:
                        - When user asks to open an app, make a call, or search contacts in ANY language (English, Hindi, Hinglish, Marathi, etc.), you MUST ALWAYS call the appropriate tool.
                        - Commands like "Open YouTube", "यूट्यूब खोलो", "youtube kholo", "WhatsApp kholo", "व्हाट्सएप खोलो", "कॉल भाई", "Mummy ko call karo", "Call Rahul" MUST invoke their respective tool immediately.
                        - Tools available:
                          1. openWhatsApp(): Opens WhatsApp.
                          2. openApp(appName): Opens apps like YouTube, Instagram, Chrome, Settings, Camera, Maps, etc. Always pass canonical English name (e.g. "YouTube", "Instagram", "Chrome", "Settings", "Camera", "Maps").
                          3. makeCall(phoneNumber): Calls a specific phone number.
                          4. callContact(contactName): Calls a contact by name or relationship (e.g. "Mom", "Mummy", "Rahul", "Dad", "brother", "भाई").
                          5. openUrl(url): Opens a web link.
                        - When you receive the tool execution response:
                          - If the action succeeded: confirm verbally in the user's language matching the rules above.
                          - If multiple contacts were found: politely ask the user which one they would like to call.
                          - If contact was not found: politely inform the user that the contact was not found.
                    """.trimIndent())
                })
            })
        }
    }

    private fun getToolDeclarations(): JSONArray {
        val toolsArray = JSONArray()
        val functionDeclarations = JSONArray()

        // 1. openWhatsApp
        functionDeclarations.put(JSONObject().apply {
            put("name", "openWhatsApp")
            put("description", "Opens WhatsApp on the Android device. Call when user wants to open WhatsApp (e.g. 'Open WhatsApp', 'WhatsApp kholo', 'WhatsApp open karo', 'WhatsApp chalao', 'व्हाट्सएप खोलो').")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phoneNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional contact phone number.")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional message to pre-fill.")
                    })
                })
            })
        })

        // 2. openApp
        functionDeclarations.put(JSONObject().apply {
            put("name", "openApp")
            put("description", "Opens an installed Android app such as YouTube, Instagram, Chrome, Settings, Camera, Maps, Clock, etc.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The name of the app to launch (e.g. 'YouTube', 'Instagram', 'Chrome', 'Settings', 'Camera', 'Maps').")
                    })
                })
                put("required", JSONArray().apply { put("appName") })
            })
        })

        // 3. makeCall
        functionDeclarations.put(JSONObject().apply {
            put("name", "makeCall")
            put("description", "Dials or initiates a phone call to a given numeric phone number (e.g. 'Call 9876543210').")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phoneNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The phone number to dial.")
                    })
                })
                put("required", JSONArray().apply { put("phoneNumber") })
            })
        })

        // 4. callContact
        functionDeclarations.put(JSONObject().apply {
            put("name", "callContact")
            put("description", "Searches device contacts by name or relationship and initiates a call (e.g. 'Call Mom', 'Mummy ko call karo', 'Call Rahul', 'कॉल भाई').")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contactName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The name or relationship of the person to call (e.g. 'Mom', 'Mummy', 'Rahul', 'Dad', 'brother').")
                    })
                })
                put("required", JSONArray().apply { put("contactName") })
            })
        })

        // 5. openUrl
        functionDeclarations.put(JSONObject().apply {
            put("name", "openUrl")
            put("description", "Opens a web link or website URL in the browser.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The URL to visit.")
                    })
                })
                put("required", JSONArray().apply { put("url") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("functionDeclarations", functionDeclarations)
        })

        return toolsArray
    }

    suspend fun processUserTurn(
        userInput: String,
        apiKey: String
    ): GeminiTurnResult = withContext(Dispatchers.IO) {
        val detectedType = LanguageDetector.detect(userInput)
        val detectedLang = detectedType.label
        VoiceLogger.logLanguageDetected(detectedLang)

        // If API key is empty or placeholder, fallback to on-device intent parser
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext processWithLocalIntentEngine(userInput, detectedType)
        }

        try {
            VoiceLogger.logGeminiSessionState("LIVE_TURN_START")

            // Append user message to history
            val userContent = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userInput) })
                })
            }
            conversationHistory.add(userContent)

            // Send request to Gemini Live model
            val firstResponse = callGeminiApi(apiKey, PRIMARY_MODEL)

            // Parse response
            val candidates = firstResponse.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            var functionCallObj: JSONObject? = null
            var initialText: String? = null
            var audioBytes: ByteArray? = null

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("functionCall")) {
                        functionCallObj = part.getJSONObject("functionCall")
                    }
                    if (part.has("text")) {
                        initialText = part.getString("text")
                    }
                    if (part.has("inlineData")) {
                        val inline = part.getJSONObject("inlineData")
                        val base64Data = inline.optString("data")
                        if (base64Data.isNotBlank()) {
                            audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        }
                    }
                }
            }

            // If Gemini decided to call a tool
            if (functionCallObj != null) {
                val functionName = functionCallObj.getString("name")
                val args = functionCallObj.optJSONObject("args") ?: JSONObject()

                Log.d(TAG, "TOOL CALL RECEIVED: name=$functionName")
                if (content != null) {
                    conversationHistory.add(content)
                }

                // Execute action via Android Action Bridge
                val executionResult = executeBridgeAction(functionName, args)

                // Send tool result back to Gemini so it confirms speech naturally
                val toolResponseContent = JSONObject().apply {
                    put("role", "tool")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", functionName)
                                put("response", JSONObject().apply {
                                    put("status", if (executionResult.toolAction.success) "success" else "error")
                                    put("summary", executionResult.toolAction.summary)
                                    put("detail", executionResult.toolAction.detail ?: "")
                                })
                            })
                        })
                    })
                }
                conversationHistory.add(toolResponseContent)

                // Second call to Gemini to generate the spoken voice response
                val secondResponse = callGeminiApi(apiKey, PRIMARY_MODEL)
                val secondCandidates = secondResponse.optJSONArray("candidates")
                val secondCandidate = secondCandidates?.optJSONObject(0)
                val secondContent = secondCandidate?.optJSONObject("content")
                val secondParts = secondContent?.optJSONArray("parts")

                var finalText = ""
                var finalAudio: ByteArray? = null

                if (secondParts != null) {
                    for (i in 0 until secondParts.length()) {
                        val p = secondParts.getJSONObject(i)
                        if (p.has("text")) {
                            finalText += p.getString("text") + " "
                        }
                        if (p.has("inlineData")) {
                            val inline = p.getJSONObject("inlineData")
                            val base64Data = inline.optString("data")
                            if (base64Data.isNotBlank()) {
                                finalAudio = Base64.decode(base64Data, Base64.DEFAULT)
                            }
                        }
                    }
                }

                if (finalText.isBlank()) {
                    finalText = executionResult.toolAction.summary
                }

                if (secondContent != null) {
                    conversationHistory.add(secondContent)
                }

                VoiceLogger.logResponseLanguage(detectedLang)
                VoiceLogger.logGeminiSessionState("LIVE_TURN_COMPLETE")

                GeminiTurnResult.Success(
                    spokenText = finalText.trim(),
                    audioBytes = finalAudio ?: audioBytes,
                    detectedLanguage = detectedLang,
                    toolAction = executionResult.toolAction,
                    pendingContacts = executionResult.pendingContacts
                )
            } else {
                // Conversational response without tool
                if (content != null) {
                    conversationHistory.add(content)
                }

                VoiceLogger.logResponseLanguage(detectedLang)
                VoiceLogger.logGeminiSessionState("LIVE_TURN_COMPLETE")

                GeminiTurnResult.Success(
                    spokenText = initialText ?: "I didn't quite catch that.",
                    audioBytes = audioBytes,
                    detectedLanguage = detectedLang
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API request failed, falling back to local engine", e)
            processWithLocalIntentEngine(userInput, detectedType)
        }
    }

    private data class BridgeExecutionResult(
        val toolAction: ToolActionInfo,
        val pendingContacts: List<ContactItem>? = null
    )

    private fun executeBridgeAction(functionName: String, args: JSONObject): BridgeExecutionResult {
        val argsMap = mutableMapOf<String, Any?>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            argsMap[key] = args.get(key)
        }

        return when (functionName) {
            "openWhatsApp" -> {
                val phone = args.optString("phoneNumber", "").takeIf { it.isNotBlank() }
                val msg = args.optString("message", "").takeIf { it.isNotBlank() }
                val res = actionBridge.executeOpenWhatsApp(phone, msg)
                BridgeExecutionResult(
                    ToolActionInfo(
                        functionName = functionName,
                        arguments = argsMap,
                        success = res.success,
                        summary = res.summary,
                        detail = res.detail,
                        appOrTarget = "WhatsApp"
                    )
                )
            }
            "openApp" -> {
                val appName = args.optString("appName", "app")
                val res = actionBridge.executeOpenApp(appName)
                BridgeExecutionResult(
                    ToolActionInfo(
                        functionName = functionName,
                        arguments = argsMap,
                        success = res.success,
                        summary = res.summary,
                        detail = res.detail,
                        appOrTarget = appName
                    )
                )
            }
            "makeCall" -> {
                val phone = args.optString("phoneNumber", "")
                val res = actionBridge.executeMakeCall(phone)
                BridgeExecutionResult(
                    ToolActionInfo(
                        functionName = functionName,
                        arguments = argsMap,
                        success = res.success,
                        summary = res.summary,
                        detail = res.detail,
                        appOrTarget = phone
                    )
                )
            }
            "callContact" -> {
                val contactName = args.optString("contactName", "")
                val outcome = actionBridge.executeCallContact(contactName)
                when (outcome) {
                    is CallContactOutcome.SingleMatch -> {
                        BridgeExecutionResult(
                            ToolActionInfo(
                                functionName = functionName,
                                arguments = argsMap,
                                success = outcome.actionResult.success,
                                summary = "Calling ${outcome.contact.name} (${outcome.contact.phoneNumber})",
                                detail = outcome.contact.phoneNumber,
                                appOrTarget = outcome.contact.name
                            )
                        )
                    }
                    is CallContactOutcome.MultipleMatches -> {
                        BridgeExecutionResult(
                            ToolActionInfo(
                                functionName = functionName,
                                arguments = argsMap,
                                success = true,
                                summary = "Found multiple contacts for '$contactName'",
                                detail = outcome.matches.joinToString { "${it.name} (${it.phoneNumber})" },
                                appOrTarget = contactName
                            ),
                            pendingContacts = outcome.matches
                        )
                    }
                    is CallContactOutcome.NotFound -> {
                        BridgeExecutionResult(
                            ToolActionInfo(
                                functionName = functionName,
                                arguments = argsMap,
                                success = false,
                                summary = outcome.message,
                                appOrTarget = contactName
                            )
                        )
                    }
                    is CallContactOutcome.PermissionDenied -> {
                        BridgeExecutionResult(
                            ToolActionInfo(
                                functionName = functionName,
                                arguments = argsMap,
                                success = false,
                                summary = outcome.message,
                                appOrTarget = contactName
                            )
                        )
                    }
                }
            }
            "openUrl" -> {
                val url = args.optString("url", "")
                val res = actionBridge.executeOpenUrl(url)
                BridgeExecutionResult(
                    ToolActionInfo(
                        functionName = functionName,
                        arguments = argsMap,
                        success = res.success,
                        summary = res.summary,
                        detail = res.detail,
                        appOrTarget = url
                    )
                )
            }
            else -> {
                BridgeExecutionResult(
                    ToolActionInfo(
                        functionName = functionName,
                        arguments = argsMap,
                        success = false,
                        summary = "Unknown function $functionName",
                        appOrTarget = functionName
                    )
                )
            }
        }
    }

    private fun callGeminiApi(apiKey: String, model: String): JSONObject {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val requestJson = JSONObject().apply {
            put("contents", JSONArray(conversationHistory))
            put("systemInstruction", getSystemInstruction())
            put("tools", getToolDeclarations())
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("responseModalities", JSONArray().apply {
                    put("TEXT")
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", UNIFIED_VOICE_NAME)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            if (model == PRIMARY_MODEL) {
                return callGeminiApiFallback(apiKey, FALLBACK_MODEL)
            }
            throw RuntimeException("Gemini API error ${response.code}: $responseBody")
        }

        return JSONObject(responseBody)
    }

    private fun callGeminiApiFallback(apiKey: String, model: String): JSONObject {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val requestJson = JSONObject().apply {
            put("contents", JSONArray(conversationHistory))
            put("systemInstruction", getSystemInstruction())
            put("tools", getToolDeclarations())
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw RuntimeException("Gemini API error ${response.code}: $responseBody")
        }

        return JSONObject(responseBody)
    }

    /**
     * Local robust NLU intent engine: accurately matches user requests in Hindi, English, and Hinglish,
     * ensuring natural wording adhering to the language-aware response guidelines.
     */
    fun processWithLocalIntentEngine(
        input: String,
        explicitType: LanguageDetector.LanguageType? = null
    ): GeminiTurnResult {
        val lower = input.trim().lowercase()
        val langType = explicitType ?: LanguageDetector.detect(input)
        val lang = langType.label

        VoiceLogger.logLanguageDetected(lang)
        VoiceLogger.logResponseLanguage(lang)

        // 1. WhatsApp variations:
        val isWhatsApp = lower.contains("whatsapp") ||
                lower.contains("व्हाट्सएप") ||
                lower.contains("वॉट्सएप") ||
                lower.contains("व्हाट्सएप्प") ||
                lower.contains("व्हाट्सऐप")

        val openVerbs = listOf(
            "open", "launch", "start",
            "kholo", "khol", "khol do", "chalao", "start karo", "open karo", "chala do",
            "ओपन", "खोलो", "खोल", "खोल दो", "चलाओ", "शुरू करो", "स्टार्ट", "चला दो"
        )

        if (isWhatsApp) {
            Log.d(TAG, "TOOL CALL RECEIVED: name=openWhatsApp")
            val res = actionBridge.executeOpenWhatsApp()

            val spoken = when (langType) {
                LanguageDetector.LanguageType.HINDI -> "ठीक है, WhatsApp खोल रहा हूँ।"
                LanguageDetector.LanguageType.HINGLISH -> "Okay, WhatsApp open kar raha hoon."
                else -> "Okay, opening WhatsApp."
            }
            return GeminiTurnResult.Success(
                spokenText = spoken,
                detectedLanguage = lang,
                toolAction = ToolActionInfo(
                    functionName = "openWhatsApp",
                    arguments = emptyMap(),
                    success = res.success,
                    summary = res.summary,
                    detail = res.detail,
                    appOrTarget = "WhatsApp"
                )
            )
        }

        // 2. Call number variations:
        val phoneRegex = Regex("""(\+?\d[\d\s-]{7,15}\d)""")
        val phoneMatch = phoneRegex.find(input)
        val isCallTrigger = lower.contains("call") || lower.contains("phone") || lower.contains("dial") ||
                lower.contains("lagao") || lower.contains("कॉल") || lower.contains("फोन") || lower.contains("लगाओ")
        if (phoneMatch != null && isCallTrigger) {
            val number = phoneMatch.value
            Log.d(TAG, "TOOL CALL RECEIVED: name=makeCall")
            val res = actionBridge.executeMakeCall(number)

            val spoken = when (langType) {
                LanguageDetector.LanguageType.HINDI -> "ठीक है, $number पर कॉल लगाया जा रहा है।"
                LanguageDetector.LanguageType.HINGLISH -> "Okay, $number par call laga raha hoon."
                else -> "Calling $number now."
            }
            return GeminiTurnResult.Success(
                spokenText = spoken,
                detectedLanguage = lang,
                toolAction = ToolActionInfo(
                    functionName = "makeCall",
                    arguments = mapOf("phoneNumber" to number),
                    success = res.success,
                    summary = res.summary,
                    detail = res.detail,
                    appOrTarget = number
                )
            )
        }

        // 3. Mixed Hindi + English specific actions:
        // E.g.: "YouTube open karo aur ye video chalao."
        if (lower.contains("youtube") && (lower.contains("video") || lower.contains("chalao") || lower.contains("play"))) {
            Log.d(TAG, "TOOL CALL RECEIVED: name=openApp (YouTube)")
            val res = actionBridge.executeOpenApp("YouTube")
            val spoken = if (langType == LanguageDetector.LanguageType.HINDI) {
                "ठीक है, YouTube खोल रहा हूँ और वीडियो चला रहा हूँ।"
            } else if (langType == LanguageDetector.LanguageType.HINGLISH) {
                "Okay, YouTube open kar raha hoon aur video play karta hoon."
            } else {
                "Okay, opening YouTube and playing the video."
            }
            return GeminiTurnResult.Success(
                spokenText = spoken,
                detectedLanguage = lang,
                toolAction = ToolActionInfo(
                    functionName = "openApp",
                    arguments = mapOf("appName" to "YouTube"),
                    success = res.success,
                    summary = res.summary,
                    appOrTarget = "YouTube"
                )
            )
        }

        // 4. App Launching variations:
        data class AppTarget(val canonicalName: String, val patterns: List<String>)
        val supportedApps = listOf(
            AppTarget("YouTube", listOf("youtube", "यूट्यूब", "यू ट्यूब")),
            AppTarget("Instagram", listOf("instagram", "insta", "इंस्टाग्राम", "इन्स्टाग्राम")),
            AppTarget("Chrome", listOf("chrome", "browser", "क्रोम", "ब्राउज़र")),
            AppTarget("Settings", listOf("settings", "setting", "सेटिंग्स", "सेटिंग")),
            AppTarget("Camera", listOf("camera", "कैमरा")),
            AppTarget("Maps", listOf("maps", "map", "गूगल मैप", "मैप्स", "मैप")),
            AppTarget("Clock", listOf("clock", "alarm", "घड़ी", "अलार्म")),
            AppTarget("Calculator", listOf("calculator", "कैलकुलेटर")),
            AppTarget("Contacts", listOf("contacts", "contact", "संपर्क", "कॉन्टैक्ट्स"))
        )

        for (app in supportedApps) {
            val matchesApp = app.patterns.any { lower.contains(it) }
            val hasOpenVerb = openVerbs.any { lower.contains(it) } || lower == app.canonicalName.lowercase()
            if (matchesApp && hasOpenVerb) {
                Log.d(TAG, "TOOL CALL RECEIVED: name=openApp (${app.canonicalName})")
                val res = actionBridge.executeOpenApp(app.canonicalName)

                val spoken = when (langType) {
                    LanguageDetector.LanguageType.HINDI -> "ठीक है, ${app.canonicalName} खोल रहा हूँ।"
                    LanguageDetector.LanguageType.HINGLISH -> "Okay, ${app.canonicalName} open kar raha hoon."
                    else -> "Okay, opening ${app.canonicalName}."
                }
                return GeminiTurnResult.Success(
                    spokenText = spoken,
                    detectedLanguage = lang,
                    toolAction = ToolActionInfo(
                        functionName = "openApp",
                        arguments = mapOf("appName" to app.canonicalName),
                        success = res.success,
                        summary = res.summary,
                        appOrTarget = app.canonicalName
                    )
                )
            }
        }

        // 5. Call Contact variations:
        val callContactKeywords = listOf(
            "call", "phone lagao", "call karo", "phone karo", "ko call", "ko phone", "call lagao", "laga do", "lagao", "dial",
            "कॉल", "फोन", "डायल", "फोन लगाओ", "कॉल करो", "फोन करो", "कॉल लगाओ", "को कॉल", "को फोन", "लगा दो"
        )
        if (callContactKeywords.any { lower.contains(it) }) {
            val extractedName = extractContactNameFromPhrase(input)
            if (extractedName.isNotBlank()) {
                Log.d(TAG, "TOOL CALL RECEIVED: name=callContact ($extractedName)")
                val outcome = actionBridge.executeCallContact(extractedName)

                return when (outcome) {
                    is CallContactOutcome.SingleMatch -> {
                        val spoken = when (langType) {
                            LanguageDetector.LanguageType.HINDI -> "ठीक है, ${outcome.contact.name} को कॉल लगाया जा रहा है।"
                            LanguageDetector.LanguageType.HINGLISH -> "Okay, ${outcome.contact.name} ko call lagaya ja raha hai."
                            else -> "Calling ${outcome.contact.name}."
                        }
                        GeminiTurnResult.Success(
                            spokenText = spoken,
                            detectedLanguage = lang,
                            toolAction = ToolActionInfo(
                                functionName = "callContact",
                                arguments = mapOf("contactName" to extractedName),
                                success = outcome.actionResult.success,
                                summary = "Calling ${outcome.contact.name} (${outcome.contact.phoneNumber})",
                                detail = outcome.contact.phoneNumber,
                                appOrTarget = outcome.contact.name
                            )
                        )
                    }
                    is CallContactOutcome.MultipleMatches -> {
                        val names = outcome.matches.joinToString(" and ") { it.name }
                        val spoken = when (langType) {
                            LanguageDetector.LanguageType.HINDI -> "मुझे '$extractedName' के लिए ${outcome.matches.size} संपर्क मिले: $names। आप किसे कॉल करना चाहते हैं?"
                            LanguageDetector.LanguageType.HINGLISH -> "Mujhe '$extractedName' ke ${outcome.matches.size} contacts mile: $names. Aap kise call karna chahte hain?"
                            else -> "I found ${outcome.matches.size} contacts for '$extractedName': $names. Which one would you like to call?"
                        }
                        GeminiTurnResult.Success(
                            spokenText = spoken,
                            detectedLanguage = lang,
                            toolAction = ToolActionInfo(
                                functionName = "callContact",
                                arguments = mapOf("contactName" to extractedName),
                                success = true,
                                summary = "Found multiple contacts for '$extractedName'",
                                detail = outcome.matches.joinToString { "${it.name} (${it.phoneNumber})" },
                                appOrTarget = extractedName
                            ),
                            pendingContacts = outcome.matches
                        )
                    }
                    is CallContactOutcome.NotFound -> {
                        val spoken = when (langType) {
                            LanguageDetector.LanguageType.HINDI -> "'$extractedName' नाम का कोई संपर्क नहीं मिला।"
                            LanguageDetector.LanguageType.HINGLISH -> "'$extractedName' naam ka koi contact nahi mila."
                            else -> "I couldn't find any contact named '$extractedName'."
                        }
                        GeminiTurnResult.Success(
                            spokenText = spoken,
                            detectedLanguage = lang,
                            toolAction = ToolActionInfo(
                                functionName = "callContact",
                                arguments = mapOf("contactName" to extractedName),
                                success = false,
                                summary = outcome.message,
                                appOrTarget = extractedName
                            )
                        )
                    }
                    is CallContactOutcome.PermissionDenied -> {
                        val spoken = when (langType) {
                            LanguageDetector.LanguageType.HINDI -> "संपर्क कॉल करने के लिए कृपया परमिशन दें।"
                            LanguageDetector.LanguageType.HINGLISH -> "Contacts call karne ke liye please permission allow karein."
                            else -> "Please allow contacts permission so I can make calls for you."
                        }
                        GeminiTurnResult.Success(
                            spokenText = spoken,
                            detectedLanguage = lang,
                            toolAction = ToolActionInfo(
                                functionName = "callContact",
                                arguments = mapOf("contactName" to extractedName),
                                success = false,
                                summary = outcome.message,
                                appOrTarget = extractedName
                            )
                        )
                    }
                }
            }
        }

        // 6. Natural Language Conversational Responses:
        // Greetings & General Questions
        if (lower.contains("नमस्ते") || lower.contains("आप कैसे हैं") || lower.contains("क्या हाल है")) {
            return GeminiTurnResult.Success(
                spokenText = "नमस्ते! मैं बिल्कुल ठीक हूँ। बताइए, आज मैं आपकी क्या मदद करूँ?",
                detectedLanguage = "Hindi"
            )
        }
        if (lower.contains("kaise ho") || lower.contains("kya haal hai") || lower.contains("kya chal raha hai")) {
            return GeminiTurnResult.Success(
                spokenText = "Main bilkul badhiya hoon! Aap bataiye, aaj kya karna hai?",
                detectedLanguage = "Hinglish"
            )
        }
        if (lower.contains("how are you") || lower.contains("what's up") || lower.contains("hello") || lower.contains("hi")) {
            val spoken = when (langType) {
                LanguageDetector.LanguageType.HINDI -> "नमस्ते! मैं पॉक्सी हूँ। मैं आपकी क्या मदद कर सकता हूँ?"
                LanguageDetector.LanguageType.HINGLISH -> "Hello! Main Poxi hoon. Bataiye main aapki kya help karoon?"
                else -> "Hello! I'm Poxi. How can I help you today?"
            }
            return GeminiTurnResult.Success(
                spokenText = spoken,
                detectedLanguage = lang
            )
        }

        // Default conversational acknowledgement strictly in user's detected language:
        val defaultResponse = when (langType) {
            LanguageDetector.LanguageType.HINDI -> "मैंने आपकी बात समझ ली है। आप मुझसे कोई भी ऐप खोलने या कॉल करने को कह सकते हैं।"
            LanguageDetector.LanguageType.HINGLISH -> "Maine aapki baat samajh li. Aap mujhse koi bhi app open karne ya call lagane ko bol sakte hain."
            else -> "I understand. You can ask me to open apps like YouTube, WhatsApp, or make calls."
        }

        return GeminiTurnResult.Success(
            spokenText = defaultResponse,
            detectedLanguage = lang
        )
    }

    private fun extractContactNameFromPhrase(phrase: String): String {
        var clean = phrase.trim().trimEnd('.', '?', '!', ',', ';', '।')
        val lower = clean.lowercase()

        // 1. Check Hindi/Hinglish patterns:
        val koPattern = Regex("""^(.*?)\s*(?:ko|par|को|पर)\s*(?:call|phone|कॉल|फोन)\s*(?:karo|lagao|karna|laga do|करो|लगाओ|लगा दो)?$""", RegexOption.IGNORE_CASE)
        val koMatch = koPattern.find(clean)
        if (koMatch != null) {
            val candidate = koMatch.groupValues[1].trim()
            if (candidate.isNotBlank() && !candidate.equals("call", ignoreCase = true) && candidate != "कॉल") {
                return candidate
            }
        }

        // 2. Check prefixes: "कॉल भाई", "Call brother", "Call Mom", "Phone Rahul"
        val removePrefixes = listOf(
            "call to", "make a call to", "can you call", "please call", "call", "phone", "dial",
            "कॉल करो", "कॉल लगाओ", "फोन करो", "फोन लगाओ", "कॉल", "फोन", "डायल"
        )
        for (prefix in removePrefixes) {
            if (lower.startsWith(prefix) || clean.startsWith(prefix)) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }

        // 3. Check suffixes
        val removeSuffixes = listOf(
            "ko call karo", "ko phone lagao", "ko call", "ko phone", "call karo", "phone karo", "please",
            "को कॉल करो", "को फोन करो", "को कॉल लगाओ", "को फोन लगाओ", "को कॉल", "को फोन", "कॉल करो", "फोन करो", "कॉल लगाओ", "फोन लगाओ", "लगाओ", "लगा दो"
        )
        for (suffix in removeSuffixes) {
            if (clean.lowercase().endsWith(suffix) || clean.endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
                break
            }
        }

        return clean.replace(Regex("""^(my|the|मेरे|मेरी)\s+""", RegexOption.IGNORE_CASE), "").trim()
    }
}
